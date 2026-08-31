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

/** Generates only rich ore veins. Standard ore generation belongs to vanilla/other plugins. */
public class GenerationListener implements Listener {
    private final Plugin plugin;
    private final NodeManager nodeManager;
    private final Random random = new Random();
    private final Map<Material, Integer> overworldWeights = new LinkedHashMap<>();
    private final Map<Material, Integer> netherWeights = new LinkedHashMap<>();
    private final ArrayDeque<ChunkJob> queue = new ArrayDeque<>();
    private final Set<ChunkJob> queued = new HashSet<>();
    private BukkitTask queueTask;
    private boolean queueEnabled;
    private int chunksPerTick;

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
        stopQueue();
        startQueueIfEnabled();
    }

    public void reloadWeights() {
        loadWeights("ore-weights", overworldWeights);
        loadWeights("ore-weights-nether", netherWeights);
    }

    private void loadWeights(String path, Map<Material, Integer> target) {
        target.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) return;
        for (String name : section.getKeys(false)) {
            try {
                Material material = Material.valueOf(name);
                int weight = section.getInt(name);
                if (weight > 0 && (material == Material.ANCIENT_DEBRIS || material.name().endsWith("_ORE"))) {
                    target.put(material, weight);
                } else if (weight > 0) {
                    plugin.getLogger().warning("Ignoring non-ore rich material: " + name);
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid ore material in " + path + ": " + name);
            }
        }
    }

    public void startQueueIfEnabled() {
        if (queueEnabled && queueTask == null) {
            queueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainQueue, 1L, 1L);
        }
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
        World world = chunk.getWorld();
        if (!enabled(world)) return;

        if (nodeManager.isChunkProcessed(world, chunk.getX(), chunk.getZ())) {
            nodeManager.processDueRespawnsInChunk(chunk);
            return;
        }

        if (queueEnabled) offer(chunk);
        else generateInChunk(chunk);
    }

    private void offer(Chunk chunk) {
        ChunkJob job = new ChunkJob(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (queued.add(job)) queue.add(job);
    }

    private void drainQueue() {
        for (int i = 0; i < chunksPerTick && !queue.isEmpty(); i++) {
            ChunkJob job = queue.poll();
            queued.remove(job);

            World world = Bukkit.getWorld(job.worldId());
            if (world == null || !world.isChunkLoaded(job.x(), job.z()) || !enabled(world)) continue;

            Chunk chunk = world.getChunkAt(job.x(), job.z());
            if (!nodeManager.isChunkProcessed(world, job.x(), job.z())) generateInChunk(chunk);
            nodeManager.processDueRespawnsInChunk(chunk);
        }
    }

    /** Generates only the plugin's rich ore system. It never generates or replaces normal ores. */
    public void generateInChunk(Chunk chunk) {
        if (!enabled(chunk.getWorld())) return;
        generateRichOres(chunk);
        nodeManager.markChunkProcessed(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    private boolean enabled(World world) {
        List<String> worlds = plugin.getConfig().getStringList("enabled-worlds");
        return worlds.isEmpty() || worlds.contains(world.getName());
    }

    private void generateRichOres(Chunk chunk) {
        World world = chunk.getWorld();
        Map<Material, Integer> weights = world.getEnvironment() == World.Environment.NETHER
                ? netherWeights : overworldWeights;
        if (weights.isEmpty()) return;

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        if (plugin.getConfig().isInt("generation.y-min")) {
            minY = Math.max(minY, plugin.getConfig().getInt("generation.y-min"));
        }
        if (plugin.getConfig().isInt("generation.y-max")) {
            maxY = Math.min(maxY, plugin.getConfig().getInt("generation.y-max"));
        }
        if (maxY < minY) return;

        int veins = Math.max(0, plugin.getConfig().getInt("generation.veins-per-chunk", 2));
        int minSize = Math.max(3, plugin.getConfig().getInt("generation.vein-size-min", 3));
        int maxSize = Math.max(minSize, plugin.getConfig().getInt("generation.vein-size-max", 5));
        int minSpacing = Math.max(1, plugin.getConfig().getInt("generation.min-spacing", 8));
        int verticalSpacing = Math.max(0, plugin.getConfig().getInt("generation.vertical-spacing", 4));
        int attempts = Math.max(veins, plugin.getConfig().getInt("generation.max-attempts-per-chunk", 96));

        int placed = 0;
        for (int attempt = 0; attempt < attempts && placed < veins; attempt++) {
            int x = (chunk.getX() << 4) + random.nextInt(16);
            int y = minY + random.nextInt(maxY - minY + 1);
            int z = (chunk.getZ() << 4) + random.nextInt(16);

            if (!isValidHost(world.getBlockAt(x, y, z).getType())) continue;
            if (!nodeManager.isAreaFree(world.getUID(), x, y, z, minSpacing, verticalSpacing)) continue;

            Material ore = roll(weights);
            if (ore != null && placeRichVein(chunk, x, y, z, ore, minSize, maxSize, minY, maxY)) {
                placed++;
            }
        }
    }

    private boolean placeRichVein(Chunk chunk, int x, int y, int z, Material ore,
                                  int minSize, int maxSize, int minY, int maxY) {
        World world = chunk.getWorld();
        int wanted = minSize + random.nextInt(maxSize - minSize + 1);
        List<int[]> positions = new ArrayList<>();
        positions.add(new int[]{x, y, z});

        int[][] directions = {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
                {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
        };

        int guard = wanted * 24;
        while (positions.size() < wanted && guard-- > 0) {
            int[] base = positions.get(random.nextInt(positions.size()));
            int[] direction = directions[random.nextInt(directions.length)];
            int nx = base[0] + direction[0];
            int ny = base[1] + direction[1];
            int nz = base[2] + direction[2];

            if (ny < minY || ny > maxY) continue;
            if ((nx >> 4) != chunk.getX() || (nz >> 4) != chunk.getZ()) continue;
            if (contains(positions, nx, ny, nz)) continue;
            if (!isValidHost(world.getBlockAt(nx, ny, nz).getType())) continue;

            positions.add(new int[]{nx, ny, nz});
        }

        // Do not create a partially generated vein unless we at least found the configured minimum size.
        if (positions.size() < minSize) return false;

        for (int[] position : positions) {
            if (nodeManager.isNode(world.getUID(), position[0], position[1], position[2])) continue;
            LocationData location = new LocationData(position[0], position[1], position[2]);
            Material displayOre = oreVariant(ore, world.getBlockAt(position[0], position[1], position[2]).getType());
            nodeManager.addNode(world.getBlockAt(location.x, location.y, location.z).getLocation(), displayOre, 1);
        }
        return true;
    }

    private record LocationData(int x, int y, int z) {}

    private boolean contains(List<int[]> list, int x, int y, int z) {
        for (int[] position : list) {
            if (position[0] == x && position[1] == y && position[2] == z) return true;
        }
        return false;
    }

    /** Any solid natural terrain is eligible; normal ores, air, liquids and vegetation are protected. */
    private boolean isValidHost(Material material) {
        if (material.isAir() || material == Material.WATER || material == Material.LAVA || !material.isSolid()) return false;
        if (material == Material.BEDROCK || material == Material.END_PORTAL_FRAME) return false;
        if (material.name().endsWith("_ORE") || material == Material.ANCIENT_DEBRIS) return false;

        String name = material.name();
        return !name.contains("LEAVES")
                && !name.contains("SAPLING")
                && !name.contains("CORAL")
                && !name.contains("MUSHROOM")
                && !name.contains("FLOWER")
                && !name.contains("GRASS")
                && !name.contains("FERN")
                && !name.contains("VINE")
                && !name.contains("CROP")
                && !name.contains("ROOTS")
                && !name.contains("BUSH")
                && !name.contains("KELP")
                && !name.contains("SEAGRASS")
                && !name.contains("BAMBOO")
                && !name.contains("CACTUS")
                && !name.contains("SUGAR_CANE");
    }

    private Material oreVariant(Material ore, Material base) {
        if (base == Material.DEEPSLATE && !ore.name().startsWith("DEEPSLATE_")) {
            try {
                return Material.valueOf("DEEPSLATE_" + ore.name());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return ore;
    }

    private Material roll(Map<Material, Integer> weights) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return null;
        int pick = random.nextInt(total);
        for (Map.Entry<Material, Integer> entry : weights.entrySet()) {
            pick -= entry.getValue();
            if (pick < 0) return entry.getKey();
        }
        return null;
    }
}
