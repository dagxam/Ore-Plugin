package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** Adds rare rich one-block ore nodes after normal Minecraft terrain/ore generation. */
public class GenerationListener implements Listener {
    private final Plugin plugin;
    private final NodeManager nodeManager;
    private final Random random = new Random();
    private final Map<Material, Integer> overworldWeights = new LinkedHashMap<>();
    private final Map<Material, Integer> netherWeights = new LinkedHashMap<>();
    private final Map<Material, Integer> endWeights = new LinkedHashMap<>();
    private final ArrayDeque<ChunkJob> queue = new ArrayDeque<>();
    private final Set<ChunkJob> queued = new HashSet<>();
    private BukkitTask queueTask;
    private boolean queueEnabled;
    private int chunksPerTick;
    private int positionsPerTick;
    private int remainingPositions;

    private record ChunkJob(UUID worldId, int x, int z) {}

    public GenerationListener(Plugin plugin, NodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
        reloadSettings();
    }

    public void reloadSettings() {
        reloadWeights();
        ConfigurationSection q = plugin.getConfig().getConfigurationSection("generation.queue");
        queueEnabled = q == null || q.getBoolean("enabled", true);
        chunksPerTick = Math.max(1, q == null ? 2 : q.getInt("chunks-per-tick", 2));
        positionsPerTick = Math.max(16, q == null ? 128 : q.getInt("positions-per-tick", 128));
        stopQueue();
        startQueueIfEnabled();
    }

    public void reloadWeights() {
        loadWeights("ore-weights", overworldWeights);
        loadWeights("ore-weights-nether", netherWeights);
        loadWeights("ore-weights-end", endWeights);
        if (overworldWeights.isEmpty()) {
            overworldWeights.put(Material.DEEPSLATE_IRON_ORE, 6);
            overworldWeights.put(Material.DEEPSLATE_GOLD_ORE, 3);
            overworldWeights.put(Material.DEEPSLATE_DIAMOND_ORE, 1);
        }
        if (netherWeights.isEmpty()) netherWeights.put(Material.ANCIENT_DEBRIS, 1);
        if (endWeights.isEmpty()) endWeights.put(Material.DEEPSLATE_DIAMOND_ORE, 1);
    }

    private void loadWeights(String path, Map<Material, Integer> target) {
        target.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) return;
        for (String name : section.getKeys(false)) {
            try {
                Material material = Material.valueOf(name);
                int weight = section.getInt(name);
                if (weight > 0 && allowed(material)) target.put(material, weight);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid ore material in " + path + ": " + name);
            }
        }
    }

    private boolean allowed(Material material) {
        return material == Material.ANCIENT_DEBRIS || material.name().endsWith("_ORE");
    }

    public void startQueueIfEnabled() {
        if (queueEnabled && queueTask == null)
            queueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainQueue, 1L, 1L);
    }

    public void stopQueue() {
        if (queueTask != null) queueTask.cancel();
        queueTask = null;
        queue.clear();
        queued.clear();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (!enabled(chunk.getWorld())) return;
        if (nodeManager.isChunkProcessed(chunk.getWorld(), chunk.getX(), chunk.getZ())) {
            nodeManager.processDueRespawnsInChunk(chunk);
            return;
        }
        if (queueEnabled) offer(chunk);
        else generateNow(chunk);
    }

    private void offer(Chunk chunk) {
        ChunkJob job = new ChunkJob(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (queued.add(job)) queue.add(job);
    }

    private void drainQueue() {
        remainingPositions = positionsPerTick;
        int chunks = chunksPerTick;
        while (chunks-- > 0 && remainingPositions > 0 && !queue.isEmpty()) {
            ChunkJob job = queue.poll();
            queued.remove(job);
            World world = Bukkit.getWorld(job.worldId());
            if (world == null || !world.isChunkLoaded(job.x(), job.z()) || !enabled(world)) continue;
            Chunk chunk = world.getChunkAt(job.x(), job.z());
            if (!nodeManager.isChunkProcessed(world, job.x(), job.z())) generateNow(chunk);
            nodeManager.processDueRespawnsInChunk(chunk);
        }
    }

    private void generateNow(Chunk chunk) {
        generateInChunk(chunk);
        nodeManager.markChunkProcessed(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    private boolean enabled(World world) {
        List<String> worlds = plugin.getConfig().getStringList("enabled-worlds");
        return worlds.isEmpty() || worlds.contains(world.getName());
    }

    public void generateInChunk(Chunk chunk) {
        World world = chunk.getWorld();
        Map<Material, Integer> weights = weightsFor(world);
        if (weights.isEmpty()) return;

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        if (plugin.getConfig().isInt("generation.y-min")) minY = Math.max(minY, plugin.getConfig().getInt("generation.y-min"));
        if (plugin.getConfig().isInt("generation.y-max")) maxY = Math.min(maxY, plugin.getConfig().getInt("generation.y-max"));
        if (maxY < minY) return;

        int target = Math.max(0, plugin.getConfig().getInt("generation.nodes-per-chunk", 2));
        int attempts = Math.max(target, plugin.getConfig().getInt("generation.max-attempts-per-chunk", 96));
        int spacing = Math.max(1, plugin.getConfig().getInt("generation.min-spacing", 8));
        int vertical = Math.max(0, plugin.getConfig().getInt("generation.vertical-spacing", 4));
        boolean replaceOres = plugin.getConfig().getBoolean("generation.replace-standard-ores", false);
        int placed = 0;
        int height = maxY - minY + 1;

        while (attempts-- > 0 && placed < target && remainingPositions-- > 0) {
            int x = (chunk.getX() << 4) + random.nextInt(16);
            int y = minY + random.nextInt(height);
            int z = (chunk.getZ() << 4) + random.nextInt(16);
            Material host = world.getBlockAt(x, y, z).getType();
            if (!replaceable(host, world, replaceOres)) continue;
            if (!nodeManager.isAreaFree(world.getUID(), x, y, z, spacing, vertical)) continue;
            Material ore = roll(weights);
            if (ore == null) continue;
            nodeManager.addNode(world.getBlockAt(x, y, z).getLocation(), ore, 1);
            placed++;
        }
    }

    private Map<Material, Integer> weightsFor(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> netherWeights;
            case THE_END -> endWeights;
            default -> overworldWeights;
        };
    }

    private boolean replaceable(Material material, World world, boolean replaceOres) {
        if (!replaceOres && material.name().endsWith("_ORE")) return false;
        return switch (world.getEnvironment()) {
            case NETHER -> material == Material.NETHERRACK || material == Material.BASALT || material == Material.BLACKSTONE;
            case THE_END -> material == Material.END_STONE;
            default -> {
                if (material == Material.STONE || material == Material.DEEPSLATE || material == Material.TUFF) yield true;
                if (!plugin.getConfig().getBoolean("generation.overworld.allow-stone-variants", true)) yield false;
                String n = material.name();
                yield n.endsWith("_STONE") || n.endsWith("ANDESITE") || n.endsWith("DIORITE") || n.endsWith("GRANITE");
            }
        };
    }

    private Material roll(Map<Material, Integer> weights) {
        int total = 0;
        for (int value : weights.values()) total += Math.max(0, value);
        if (total <= 0) return null;
        int pick = random.nextInt(total);
        for (Map.Entry<Material, Integer> entry : weights.entrySet()) {
            pick -= Math.max(0, entry.getValue());
            if (pick < 0) return entry.getKey();
        }
        return null;
    }
}
