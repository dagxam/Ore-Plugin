package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** Generation with bounded per-tick budgets and chunk-indexed spacing checks. */
public class GenerationListener implements Listener {
    private final Plugin plugin;
    private final NodeManager nodeManager;
    private final Random random = new Random();
    private Map<Material, Integer> weightsDefault = new LinkedHashMap<>();
    private Map<Material, Integer> weightsOverworld;
    private Map<Material, Integer> weightsNether;
    private boolean queueEnabled;
    private int chunksPerTick;
    private int positionsPerTick;
    private int fillAttemptsPerTick;
    private int remainingPositions;
    private int remainingFillAttempts;
    private final ArrayDeque<ChunkJob> chunkQueue = new ArrayDeque<>();
    private final Set<ChunkJob> queued = new HashSet<>();
    private BukkitTask queueTask;
    private final EnumMap<Material, Boolean> overworldCache = new EnumMap<>(Material.class);
    private final EnumMap<Material, Boolean> netherCache = new EnumMap<>(Material.class);

    private record ChunkJob(UUID world, int x, int z) {}

    public GenerationListener(Plugin plugin, NodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
        reloadSettings();
    }

    public void reloadSettings() {
        reloadWeights();
        ConfigurationSection q = plugin.getConfig().getConfigurationSection("generation.queue");
        queueEnabled = q != null && q.getBoolean("enabled", true);
        chunksPerTick = Math.max(1, q == null ? 2 : q.getInt("chunks-per-tick", 2));
        positionsPerTick = Math.max(32, q == null ? 300 : q.getInt("positions-per-tick", 300));
        fillAttemptsPerTick = Math.max(16, q == null ? 180 : q.getInt("fill-attempts-per-tick", 180));
        overworldCache.clear();
        netherCache.clear();
        stopQueue();
        startQueueIfEnabled();
    }

    public void reloadWeights() {
        weightsDefault = loadWeights("ore-weights");
        Map<Material, Integer> ow = loadWeights("ore-weights-overworld");
        Map<Material, Integer> ne = loadWeights("ore-weights-nether");
        weightsOverworld = ow.isEmpty() ? null : ow;
        weightsNether = ne.isEmpty() ? null : ne;
        if (weightsDefault.isEmpty()) {
            weightsDefault = new LinkedHashMap<>();
            weightsDefault.put(Material.DEEPSLATE_REDSTONE_ORE, 8);
            weightsDefault.put(Material.DEEPSLATE_IRON_ORE, 6);
            weightsDefault.put(Material.DEEPSLATE_GOLD_ORE, 3);
            weightsDefault.put(Material.DEEPSLATE_DIAMOND_ORE, 1);
        }
    }

    private Map<Material, Integer> loadWeights(String path) {
        Map<Material, Integer> out = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) return out;
        for (String name : section.getKeys(false)) {
            try {
                Material material = Material.valueOf(name);
                int weight = section.getInt(name);
                if (weight > 0 && allowed(material)) out.put(material, weight);
                else if (weight > 0) plugin.getLogger().warning("Blocked non-ore material: " + name);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid material in " + path + ": " + name);
            }
        }
        return out;
    }

    private boolean allowed(Material material) {
        return material == Material.ANCIENT_DEBRIS || material == Material.NETHERITE_SCRAP || material.name().endsWith("_ORE");
    }

    public void startQueueIfEnabled() {
        if (queueEnabled && queueTask == null)
            queueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainQueue, 1L, 1L);
    }

    public void stopQueue() {
        if (queueTask != null) queueTask.cancel();
        queueTask = null;
        chunkQueue.clear();
        queued.clear();
    }

    private void offerChunk(Chunk chunk) {
        ChunkJob job = new ChunkJob(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (queued.add(job)) chunkQueue.add(job);
    }

    private void drainQueue() {
        remainingPositions = positionsPerTick;
        remainingFillAttempts = fillAttemptsPerTick;
        int chunks = chunksPerTick;
        while (chunks-- > 0 && !chunkQueue.isEmpty() && remainingPositions > 0) {
            ChunkJob job = chunkQueue.poll();
            queued.remove(job);
            World world = Bukkit.getWorld(job.world());
            if (world == null || !enabled(world) || !world.isChunkLoaded(job.x(), job.z())) continue;
            Chunk chunk = world.getChunkAt(job.x(), job.z());
            if (!nodeManager.isChunkProcessed(world, job.x(), job.z())) {
                generateInChunk(chunk);
                nodeManager.markChunkProcessed(world, job.x(), job.z());
            }
            nodeManager.processDueRespawnsInChunk(chunk);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (!enabled(chunk.getWorld())) return;
        if (nodeManager.isChunkProcessed(chunk.getWorld(), chunk.getX(), chunk.getZ())) {
            nodeManager.processDueRespawnsInChunk(chunk);
        } else if (queueEnabled) offerChunk(chunk);
        else {
            remainingPositions = Integer.MAX_VALUE;
            remainingFillAttempts = Integer.MAX_VALUE;
            generateInChunk(chunk);
            nodeManager.markChunkProcessed(chunk.getWorld(), chunk.getX(), chunk.getZ());
            nodeManager.processDueRespawnsInChunk(chunk);
        }
    }

    private boolean enabled(World world) { return plugin.getConfig().getStringList("enabled-worlds").contains(world.getName()); }

    public void generateInChunk(Chunk chunk) {
        World world = chunk.getWorld();
        if (world.getEnvironment() != Environment.NORMAL && world.getEnvironment() != Environment.NETHER) return;
        Map<Material, Integer> weights = world.getEnvironment() == Environment.NETHER && weightsNether != null ? weightsNether : world.getEnvironment() == Environment.NORMAL && weightsOverworld != null ? weightsOverworld : weightsDefault;
        if (weights.isEmpty()) return;

        int minY = world.getMinHeight();
        int maxY = Math.min(world.getMaxHeight() - 1, minY + Math.max(4, plugin.getConfig().getInt("generation.default-bedrock-band", 8)));
        if (plugin.getConfig().isInt("generation.y-min")) minY = Math.max(world.getMinHeight(), plugin.getConfig().getInt("generation.y-min"));
        if (plugin.getConfig().isInt("generation.y-max")) maxY = Math.min(world.getMaxHeight() - 1, plugin.getConfig().getInt("generation.y-max"));
        if (maxY < minY) return;

        int spacing = Math.max(1, plugin.getConfig().getInt("generation.cluster.min-spacing", 4));
        int vertical = Math.max(0, plugin.getConfig().getInt("generation.cluster.vertical-spacing", 2));
        int clusterMin = Math.max(1, plugin.getConfig().getInt("generation.cluster.size-min", 1));
        int clusterMax = Math.max(clusterMin, plugin.getConfig().getInt("generation.cluster.size-max", 3));
        int maxClusters = Math.max(0, plugin.getConfig().getInt("generation.max-per-chunk", 24));
        int hardCap = Math.max(50, plugin.getConfig().getInt("generation.max-attempts-per-chunk", 900));
        int target = Math.min(maxClusters, Math.max(0, plugin.getConfig().getInt("generation.target-per-chunk", 12)));
        int attempts = Math.min(hardCap, Math.max(target * 8, 100));
        int yLen = maxY - minY + 1;
        int placed = 0;
        List<int[]> centers = new ArrayList<>();

        while (attempts-- > 0 && placed < target && remainingPositions-- > 0) {
            int x = (chunk.getX() << 4) + random.nextInt(16);
            int y = minY + random.nextInt(yLen);
            int z = (chunk.getZ() << 4) + random.nextInt(16);
            Material host = chunk.getBlock(x & 15, y, z & 15).getType();
            if (!replaceable(host, world.getEnvironment())) continue;
            if (!farFromCenters(centers, x, y, z, spacing, vertical)) continue;
            if (!nodeManager.isAreaFree(world.getUID(), x, y, z, spacing, vertical)) continue;
            Material ore = roll(weights, world);
            if (ore == null) continue;
            if (placeCluster(chunk, x, y, z, ore, clusterMin, clusterMax, minY, maxY, world.getEnvironment())) {
                centers.add(new int[]{x, y, z});
                placed++;
            }
        }
    }

    private boolean placeCluster(Chunk chunk, int x, int y, int z, Material ore, int min, int max, int minY, int maxY, Environment env) {
        int wanted = min + random.nextInt(max - min + 1);
        List<int[]> positions = new ArrayList<>();
        positions.add(new int[]{x, y, z});
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int i = 0; i < wanted - 1 && remainingFillAttempts-- > 0; i++) {
            int[] base = positions.get(random.nextInt(positions.size()));
            int[] d = dirs[random.nextInt(dirs.length)];
            int nx = base[0] + d[0], ny = base[1] + d[1], nz = base[2] + d[2];
            if (ny < minY || ny > maxY || (nx >> 4) != chunk.getX() || (nz >> 4) != chunk.getZ()) continue;
            if (chunk.getBlock(nx & 15, ny, nz & 15).getType() != Material.BEDROCK && replaceable(chunk.getBlock(nx & 15, ny, nz & 15).getType(), env)) positions.add(new int[]{nx, ny, nz});
        }
        boolean placed = false;
        for (int[] p : positions) {
            if (nodeManager.isNode(chunk.getWorld().getUID(), p[0], p[1], p[2])) continue;
            nodeManager.addNode(chunk.getWorld().getBlockAt(p[0], p[1], p[2]).getLocation(), ore, nodeManager.randomHits());
            placed = true;
        }
        return placed;
    }

    private boolean farFromCenters(List<int[]> centers, int x, int y, int z, int horizontal, int vertical) {
        int h2 = horizontal * horizontal;
        for (int[] c : centers) {
            int dx = c[0] - x, dz = c[2] - z;
            if (dx * dx + dz * dz <= h2 && Math.abs(c[1] - y) <= vertical) return false;
        }
        return true;
    }

    private boolean replaceable(Material material, Environment env) {
        EnumMap<Material, Boolean> cache = env == Environment.NETHER ? netherCache : overworldCache;
        Boolean cached = cache.get(material);
        if (cached != null) return cached;
        boolean result;
        if (env == Environment.NETHER) result = material == Material.NETHERRACK || material == Material.BASALT || material == Material.BLACKSTONE;
        else {
            result = material == Material.DEEPSLATE || material == Material.STONE || material == Material.TUFF;
            if (!result && plugin.getConfig().getBoolean("generation.overworld.allow-stone-variants", true)) {
                String n = material.name();
                result = n.endsWith("_STONE") || n.endsWith("ANDESITE") || n.endsWith("DIORITE") || n.endsWith("GRANITE");
            }
        }
        cache.put(material, result);
        return result;
    }

    private Material roll(Map<Material, Integer> weights, World world) {
        List<Map.Entry<Material,Integer>> valid = new ArrayList<>();
        int total = 0;
        for (Map.Entry<Material,Integer> e : weights.entrySet()) {
            if (world.getEnvironment() != Environment.NETHER && e.getKey() == Material.NETHERITE_SCRAP) continue;
            valid.add(e); total += e.getValue();
        }
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Map.Entry<Material,Integer> e : valid) { r -= e.getValue(); if (r < 0) return e.getKey(); }
        return valid.get(valid.size() - 1).getKey();
    }
}
