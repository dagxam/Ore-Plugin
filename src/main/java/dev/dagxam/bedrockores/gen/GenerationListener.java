package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
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

/**
 * Deterministic ore-node generator.
 *
 * Generation is derived from the world seed and chunk coordinates rather than
 * a runtime Random instance. Vein candidates are evaluated in a 3x3 chunk
 * neighbourhood so a vein can cross chunk borders without a hard seam.
 */
public class GenerationListener implements Listener {
    private static final long GENERATOR_SALT = 0x4F524556325F4F52L;

    private final Plugin plugin;
    private final NodeManager nodeManager;

    private Map<Material, Integer> weightsDefault = new LinkedHashMap<>();
    private Map<Material, Integer> weightsOverworld;
    private Map<Material, Integer> weightsNether;

    private boolean queueEnabled;
    private int chunksPerTick;

    private final ArrayDeque<long[]> chunkQueue = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>();
    private BukkitTask queueTask;

    private final EnumMap<Material, Boolean> replaceableOverworldCache = new EnumMap<>(Material.class);
    private final EnumMap<Material, Boolean> replaceableNetherCache = new EnumMap<>(Material.class);

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
        stopQueue();
        startQueueIfEnabled();
    }

    public void reloadWeights() {
        weightsDefault = loadWeightsFiltered("ore-weights");
        Map<Material, Integer> ow = loadWeightsFiltered("ore-weights-overworld");
        Map<Material, Integer> ne = loadWeightsFiltered("ore-weights-nether");
        weightsOverworld = ow.isEmpty() ? null : ow;
        weightsNether = ne.isEmpty() ? null : ne;

        if (weightsDefault.isEmpty()) {
            LinkedHashMap<Material, Integer> defaults = new LinkedHashMap<>();
            defaults.put(Material.DEEPSLATE_REDSTONE_ORE, 8);
            defaults.put(Material.DEEPSLATE_IRON_ORE, 6);
            defaults.put(Material.DEEPSLATE_GOLD_ORE, 3);
            defaults.put(Material.DEEPSLATE_COPPER_ORE, 4);
            defaults.put(Material.DEEPSLATE_COAL_ORE, 4);
            defaults.put(Material.DEEPSLATE_LAPIS_ORE, 3);
            defaults.put(Material.DEEPSLATE_DIAMOND_ORE, 1);
            defaults.put(Material.DEEPSLATE_EMERALD_ORE, 1);
            defaults.put(Material.ANCIENT_DEBRIS, 1);
            weightsDefault = defaults;
        }
    }

    public void startQueueIfEnabled() {
        if (!queueEnabled || queueTask != null) return;
        queueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainQueue, 1L, 1L);
        plugin.getLogger().info("[BedrockOres] Generation queue started: chunks-per-tick=" + chunksPerTick);
    }

    public void stopQueue() {
        if (queueTask != null) {
            try { queueTask.cancel(); } catch (Throwable ignored) {}
            queueTask = null;
        }
        chunkQueue.clear();
        queuedKeys.clear();
    }

    private void offerChunk(Chunk chunk) {
        UUID worldId = chunk.getWorld().getUID();
        String key = chunkKey(worldId, chunk.getX(), chunk.getZ());
        if (!queuedKeys.add(key)) return;
        chunkQueue.add(new long[]{worldId.getMostSignificantBits(), worldId.getLeastSignificantBits(), chunk.getX(), chunk.getZ()});
    }

    private void drainQueue() {
        int budget = chunksPerTick;
        while (budget-- > 0 && !chunkQueue.isEmpty()) {
            long[] entry = chunkQueue.poll();
            UUID worldId = new UUID(entry[0], entry[1]);
            int cx = (int) entry[2];
            int cz = (int) entry[3];
            queuedKeys.remove(chunkKey(worldId, cx, cz));

            World world = Bukkit.getWorld(worldId);
            if (world == null || !isEnabledWorld(world)) continue;
            if (!world.isChunkLoaded(cx, cz)) continue;

            Chunk chunk = world.getChunkAt(cx, cz);
            if (!nodeManager.isChunkProcessed(world, cx, cz)) {
                generateInChunk(chunk);
                nodeManager.markChunkProcessed(world, cx, cz);
            }
            nodeManager.processDueRespawnsInChunk(chunk);
        }
    }

    private boolean isEnabledWorld(World world) {
        return plugin.getConfig().getStringList("enabled-worlds").contains(world.getName());
    }

    private static String chunkKey(UUID world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    private Map<Material, Integer> loadWeightsFiltered(String section) {
        LinkedHashMap<Material, Integer> map = new LinkedHashMap<>();
        ConfigurationSection sectionData = plugin.getConfig().getConfigurationSection(section);
        if (sectionData == null) return map;

        for (String key : sectionData.getKeys(false)) {
            Material material;
            try {
                material = Material.valueOf(key);
            } catch (Exception ex) {
                plugin.getLogger().warning("[BedrockOres] Invalid material in " + section + ": " + key);
                continue;
            }

            int weight = sectionData.getInt(key);
            if (weight <= 0) continue;
            if (!isAllowedOreMaterial(material)) {
                plugin.getLogger().warning("[BedrockOres] Blocked non-ore material in " + section + ": " + material);
                continue;
            }
            map.put(material, weight);
        }
        return map;
    }

    private boolean isAllowedOreMaterial(Material material) {
        return material == Material.ANCIENT_DEBRIS
                || material == Material.NETHERITE_SCRAP
                || material.name().endsWith("_ORE");
    }

    private Map<Material, Integer> weightsFor(World world) {
        if (world.getEnvironment() == Environment.NETHER && weightsNether != null) return weightsNether;
        if (world.getEnvironment() == Environment.NORMAL && weightsOverworld != null) return weightsOverworld;
        return weightsDefault;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!isEnabledWorld(world)) return;

        Chunk chunk = event.getChunk();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        if (nodeManager.isChunkProcessed(world, cx, cz)) {
            nodeManager.processDueRespawnsInChunk(chunk);
            return;
        }

        if (queueEnabled) {
            offerChunk(chunk);
        } else {
            generateInChunk(chunk);
            nodeManager.markChunkProcessed(world, cx, cz);
            nodeManager.processDueRespawnsInChunk(chunk);
        }
    }

    /**
     * Generates the target chunk from deterministic candidates belonging to
     * the surrounding 3x3 chunks. Only loaded chunks are modified.
     */
    public void generateInChunk(Chunk targetChunk) {
        World world = targetChunk.getWorld();
        Environment environment = world.getEnvironment();
        if (environment != Environment.NORMAL && environment != Environment.NETHER) return;

        Map<Material, Integer> weights = weightsFor(world);
        if (weights.isEmpty()) return;

        int minY = configuredMinY(world);
        int maxY = configuredMaxY(world);
        if (maxY < minY) return;

        int targetChunkX = targetChunk.getX();
        int targetChunkZ = targetChunk.getZ();
        int maxClusters = Math.max(0, plugin.getConfig().getInt("generation.max-per-chunk", 24));
        int targetClusters = Math.max(0, plugin.getConfig().getInt("generation.target-per-chunk", 12));
        int attempts = Math.max(100, plugin.getConfig().getInt("generation.max-attempts-per-chunk", 1800));
        int clusterMin = Math.max(1, plugin.getConfig().getInt("generation.cluster.size-min", 3));
        int clusterMax = Math.max(clusterMin, plugin.getConfig().getInt("generation.cluster.size-max", 6));
        int spacing = Math.max(1, plugin.getConfig().getInt("generation.cluster.min-spacing", 4));
        double density = Math.max(0.0D, plugin.getConfig().getDouble("generation.density-multiplier", 1.0D));
        double chance = Math.max(0.0D, plugin.getConfig().getDouble("generation.chance-per-block", 0.008D)) * density;

        int placedCenters = 0;
        int considered = 0;
        List<long[]> acceptedCenters = new ArrayList<>();

        for (int centerChunkX = targetChunkX - 1; centerChunkX <= targetChunkX + 1; centerChunkX++) {
            for (int centerChunkZ = targetChunkZ - 1; centerChunkZ <= targetChunkZ + 1; centerChunkZ++) {
                long seed = mixSeed(world.getSeed() ^ GENERATOR_SALT, centerChunkX, centerChunkZ);
                SplittableRandom random = new SplittableRandom(seed);
                int localAttempts = Math.max(1, attempts / 9);

                for (int i = 0; i < localAttempts && considered < attempts; i++) {
                    considered++;
                    int x = (centerChunkX << 4) + random.nextInt(16);
                    int z = (centerChunkZ << 4) + random.nextInt(16);
                    int y = minY + random.nextInt(maxY - minY + 1);

                    // A deterministic probability controls density. target-per-chunk
                    // remains a soft target, while max-per-chunk is the hard cap.
                    double threshold = Math.min(1.0D, chance * 16.0D);
                    if (random.nextDouble() > threshold && placedCenters >= targetClusters) continue;
                    if (!isTargetCoordinate(x, z, targetChunkX, targetChunkZ)) continue;
                    if (!isCenterReplaceable(world, x, y, z, environment)) continue;
                    if (!farEnough(acceptedCenters, x, y, z, spacing)) continue;

                    Material ore = rollOre(weights, environment, random);
                    if (ore == null) continue;

                    int size = clusterMin + random.nextInt(clusterMax - clusterMin + 1);
                    if (placeVein(world, x, y, z, ore, size, minY, maxY, environment, random)) {
                        acceptedCenters.add(new long[]{x, y, z});
                        placedCenters++;
                        if (placedCenters >= maxClusters) return;
                    }
                }
            }
        }
    }

    private boolean isTargetCoordinate(int x, int z, int chunkX, int chunkZ) {
        return (x >> 4) == chunkX && (z >> 4) == chunkZ;
    }

    private int configuredMinY(World world) {
        if (plugin.getConfig().isInt("generation.y-min")) {
            return Math.max(world.getMinHeight(), plugin.getConfig().getInt("generation.y-min"));
        }
        return world.getMinHeight();
    }

    private int configuredMaxY(World world) {
        if (plugin.getConfig().isInt("generation.y-max")) {
            return Math.min(world.getMaxHeight() - 1, plugin.getConfig().getInt("generation.y-max"));
        }
        int band = Math.max(4, plugin.getConfig().getInt("generation.default-bedrock-band", 8));
        return Math.min(world.getMaxHeight() - 1, world.getMinHeight() + band);
    }

    private boolean isCenterReplaceable(World world, int x, int y, int z, Environment environment) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        return isReplaceableCached(world.getBlockAt(x, y, z).getType(), environment);
    }

    private boolean isReplaceableCached(Material material, Environment environment) {
        if (environment == Environment.NORMAL) {
            Boolean cached = replaceableOverworldCache.get(material);
            if (cached != null) return cached;
            boolean value = isReplaceableOverworld(material);
            replaceableOverworldCache.put(material, value);
            return value;
        }
        if (environment == Environment.NETHER) {
            Boolean cached = replaceableNetherCache.get(material);
            if (cached != null) return cached;
            boolean value = material == Material.NETHERRACK || material == Material.BASALT || material == Material.BLACKSTONE;
            replaceableNetherCache.put(material, value);
            return value;
        }
        return false;
    }

    private boolean isReplaceableOverworld(Material material) {
        if (material == Material.DEEPSLATE || material == Material.STONE || material == Material.TUFF) return true;
        if (!plugin.getConfig().getBoolean("generation.overworld.allow-stone-variants", true)) return false;
        String name = material.name();
        return name.endsWith("_STONE") || name.endsWith("ANDESITE") || name.endsWith("DIORITE") || name.endsWith("GRANITE");
    }

    private boolean farEnough(List<long[]> centers, int x, int y, int z, int spacing) {
        int horizontal = spacing * spacing;
        int vertical = Math.max(2, spacing / 2);
        for (long[] center : centers) {
            int dx = (int) center[0] - x;
            int dy = (int) center[1] - y;
            int dz = (int) center[2] - z;
            if (dx * dx + dz * dz <= horizontal && Math.abs(dy) <= vertical) return false;
        }
        return true;
    }

    private boolean placeVein(World world,
                              int startX,
                              int startY,
                              int startZ,
                              Material ore,
                              int size,
                              int minY,
                              int maxY,
                              Environment environment,
                              SplittableRandom random) {
        List<long[]> vein = new ArrayList<>(size);
        vein.add(new long[]{startX, startY, startZ});

        int[][] directions = {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
        };

        for (int i = 1; i < size; i++) {
            long[] base = vein.get(random.nextInt(vein.size()));
            int[] direction = directions[random.nextInt(directions.length)];
            int nx = (int) base[0] + direction[0];
            int ny = (int) base[1] + direction[1];
            int nz = (int) base[2] + direction[2];

            if (ny < minY || ny > maxY) continue;
            if (!world.isChunkLoaded(nx >> 4, nz >> 4)) continue;
            if (!isReplaceableCached(world.getBlockAt(nx, ny, nz).getType(), environment)) continue;
            if (containsPosition(vein, nx, ny, nz)) continue;
            vein.add(new long[]{nx, ny, nz});
        }

        int placed = 0;
        for (long[] position : vein) {
            int x = (int) position[0];
            int y = (int) position[1];
            int z = (int) position[2];
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!isReplaceableCached(world.getBlockAt(x, y, z).getType(), environment)) continue;
            if (nodeManager.isNode(world.getUID(), x, y, z)) continue;

            nodeManager.addNode(new Location(world, x, y, z), ore, nodeManager.randomHits());
            placed++;
        }
        return placed > 0;
    }

    private boolean containsPosition(List<long[]> positions, int x, int y, int z) {
        for (long[] position : positions) {
            if (position[0] == x && position[1] == y && position[2] == z) return true;
        }
        return false;
    }

    private Material rollOre(Map<Material, Integer> weights, Environment environment, SplittableRandom random) {
        int total = 0;
        for (Map.Entry<Material, Integer> entry : weights.entrySet()) {
            if (isAllowedForEnvironment(entry.getKey(), environment)) total += entry.getValue();
        }
        if (total <= 0) return null;

        int roll = random.nextInt(total);
        int accumulated = 0;
        for (Map.Entry<Material, Integer> entry : weights.entrySet()) {
            Material material = entry.getKey();
            if (!isAllowedForEnvironment(material, environment)) continue;
            accumulated += entry.getValue();
            if (roll < accumulated) return material;
        }
        return null;
    }

    private boolean isAllowedForEnvironment(Material material, Environment environment) {
        if (material == Material.NETHERITE_SCRAP || material == Material.ANCIENT_DEBRIS) {
            return environment == Environment.NETHER;
        }
        return material.name().endsWith("_ORE") && environment == Environment.NORMAL;
    }

    /** Stable 64-bit mixer for deterministic chunk seeds. */
    private static long mixSeed(long seed, int chunkX, int chunkZ) {
        long value = seed;
        value ^= (long) chunkX * 0x9E3779B97F4A7C15L;
        value ^= (long) chunkZ * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }
}
