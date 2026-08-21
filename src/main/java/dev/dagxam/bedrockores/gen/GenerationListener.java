package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Генератор рудных узлов, совместимый с WaterWorld.
 *
 * WaterWorld сначала создаёт фактический рельеф через ChunkGenerator,
 * а гору достраивает в ChunkPopulateEvent. Поэтому Ore-Plugin никогда
 * не угадывает геометрию острова по собственной формуле: перед заменой
 * блока проверяется уже существующий материал мира.
 */
public final class GenerationListener implements Listener {
    private static final long GENERATOR_SALT = 0x4F524556325F4F52L;
    private static final int VEIN_SEARCH_RADIUS_CHUNKS = 1;

    private final Plugin plugin;
    private final NodeManager nodeManager;
    private boolean queueEnabled;
    private int chunksPerTick;
    private BukkitTask queueTask;
    private final ArrayDeque<long[]> chunkQueue = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>();

    public GenerationListener(Plugin plugin, NodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
        reloadSettings();
    }

    public void reloadSettings() {
        ConfigurationSection queue = plugin.getConfig().getConfigurationSection("generation.queue");
        if (queue == null) {
            queue = plugin.getConfig().getConfigurationSection("генерация.очередь");
        }
        queueEnabled = queue == null || queue.getBoolean(queue.contains("enabled") ? "enabled" : "включено", true);
        String chunksKey = queue != null && queue.contains("chunks-per-tick") ? "chunks-per-tick" : "чанков-за-тик";
        chunksPerTick = Math.max(1, queue == null ? 2 : queue.getInt(chunksKey, 2));
        stopQueue();
        startQueueIfEnabled();
    }

    public void startQueueIfEnabled() {
        if (!queueEnabled || queueTask != null) return;
        queueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainQueue, 1L, 1L);
        plugin.getLogger().info("Очередь генерации руды запущена. Чанков за тик: " + chunksPerTick);
    }

    public void stopQueue() {
        if (queueTask != null) {
            try { queueTask.cancel(); } catch (Throwable ignored) { }
            queueTask = null;
        }
        chunkQueue.clear();
        queuedKeys.clear();
    }

    private boolean isEnabledWorld(World world) {
        return world != null && plugin.getConfig().getStringList("разрешённые-мирами").contains(world.getName());
    }

    private boolean isWaterWorld(World world) {
        if (world == null || world.getEnvironment() != Environment.NORMAL) return false;
        if (!plugin.getConfig().getBoolean("integrations.waterworld.включено", true)) return false;
        String configured = plugin.getConfig().getString("integrations.waterworld.имя-мира", "");
        return configured == null || configured.isBlank() || configured.equals(world.getName());
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
            if (world == null || !isEnabledWorld(world) || !world.isChunkLoaded(cx, cz)) continue;
            processLoadedChunk(world.getChunkAt(cx, cz));
        }
    }

    private void processLoadedChunk(Chunk chunk) {
        World world = chunk.getWorld();
        nodeManager.loadChunkAsync(chunk, () -> {
            if (!world.isChunkLoaded(chunk.getX(), chunk.getZ())) return;
            if (!nodeManager.isChunkProcessed(world, chunk.getX(), chunk.getZ())) {
                generateInChunk(chunk);
                nodeManager.markChunkProcessed(world, chunk.getX(), chunk.getZ());
            }
            nodeManager.processDueRespawnsInChunk(chunk);
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!isEnabledWorld(world)) return;
        Chunk chunk = event.getChunk();
        if (queueEnabled) offerChunk(chunk);
        else processLoadedChunk(chunk);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        nodeManager.unloadChunk(event.getChunk());
    }

    /**
     * Генерирует рудные узлы только в реальные host-блоки текущего чанка.
     * Для WaterWorld это означает: камень острова/горы, подводный грунт,
     * песок и гравий; вода и воздух никогда не заменяются рудой.
     */
    public void generateInChunk(Chunk targetChunk) {
        if (targetChunk == null) return;
        World world = targetChunk.getWorld();
        Environment environment = world.getEnvironment();
        if (environment != Environment.NORMAL && environment != Environment.NETHER) return;

        ConfigurationSection ores = plugin.getConfig().getConfigurationSection("generation.ores");
        if (ores == null) ores = plugin.getConfig().getConfigurationSection("генерация.руды");
        if (ores == null) return;

        int maxNodes = getInt("generation.max-per-chunk", "генерация.максимум-узлов-на-чанк", 24);
        int attempts = getInt("generation.max-attempts-per-chunk", "генерация.максимум-попыток-на-чанк", 1800);
        int defaultMinSize = getInt("generation.cluster.size-min", "генерация.жила.размер-минимум", 3);
        int defaultMaxSize = getInt("generation.cluster.size-max", "генерация.жила.размер-максимум", 6);
        int spacing = getInt("generation.cluster.min-spacing", "генерация.жила.минимальная-дистанция", 4);
        double globalDensity = getDouble("generation.density-multiplier", "генерация.общий-множитель-плотности", 1.0D);
        double baseChance = getDouble("generation.chance-per-block", "генерация.шанс-на-блок", 0.008D);

        if (maxNodes <= 0 || attempts <= 0) return;

        List<OreProfile> profiles = loadProfiles(ores, environment, defaultMinSize, defaultMaxSize, globalDensity);
        if (profiles.isEmpty()) return;

        int targetCx = targetChunk.getX();
        int targetCz = targetChunk.getZ();
        int minProfileY = minY(profiles);
        int maxProfileY = maxY(profiles);
        if (maxProfileY < minProfileY) return;

        int placed = 0;
        int inspected = 0;
        List<long[]> centers = new ArrayList<>();

        // Центры берутся из 3x3 чанков. Сам блок изменяется только внутри targetChunk.
        // Поэтому жила может продолжаться через границу и достроиться при загрузке соседа.
        for (int centerCx = targetCx - VEIN_SEARCH_RADIUS_CHUNKS; centerCx <= targetCx + VEIN_SEARCH_RADIUS_CHUNKS; centerCx++) {
            for (int centerCz = targetCz - VEIN_SEARCH_RADIUS_CHUNKS; centerCz <= targetCz + VEIN_SEARCH_RADIUS_CHUNKS; centerCz++) {
                long seed = mixSeed(world.getSeed() ^ GENERATOR_SALT, centerCx, centerCz);
                SplittableRandom random = new SplittableRandom(seed);
                int candidates = Math.max(1, (int) Math.ceil(16.0D * baseChance));
                if (centerCx == targetCx && centerCz == targetCz) candidates *= 3;

                for (int i = 0; i < candidates && inspected++ < attempts && placed < maxNodes; i++) {
                    int x = (centerCx << 4) + random.nextInt(16);
                    int z = (centerCz << 4) + random.nextInt(16);
                    int y = minProfileY + random.nextInt(maxProfileY - minProfileY + 1);

                    OreProfile profile = rollProfile(profiles, random, y);
                    if (profile == null) continue;

                    double layerDensity = getWaterWorldDensity(world, x, y, z);
                    if (layerDensity <= 0.0D) continue;

                    double chance = Math.min(1.0D, profile.density * layerDensity);
                    if (random.nextDouble() > chance) continue;
                    if (!farEnough(centers, x, y, z, spacing)) continue;

                    int size = profile.minSize + random.nextInt(profile.maxSize - profile.minSize + 1);
                    int changed = placeVeinPart(world, targetCx, targetCz, x, y, z,
                            profile.material, size, profile.minY, profile.maxY, environment, random);
                    if (changed > 0) {
                        centers.add(new long[]{x, y, z});
                        placed += changed;
                    }
                }
            }
        }
    }

    /**
     * Для WaterWorld классификация основана на фактическом рельефе.
     * Важно: не используется фиксированный радиус острова из Ore-Plugin,
     * потому что WaterWorld сам задаёт radius/slope-radius и достраивает гору.
     */
    private double getWaterWorldDensity(World world, int x, int y, int z) {
        if (!isWaterWorld(world)) return 1.0D;

        Material existing = world.getBlockAt(x, y, z).getType();
        if (isForbidden(existing)) return 0.0D;

        int seaLevel = world.getSeaLevel();
        int surfaceY = world.getHighestBlockYAt(x, z);
        Material surface = world.getBlockAt(x, surfaceY, z).getType();

        if (surfaceY > seaLevel && isLandSurface(surface)) {
            if (surfaceY >= 85 && plugin.getConfig().getBoolean("generation.waterworld.mountain.enabled", true)) {
                return plugin.getConfig().getDouble("generation.waterworld.mountain.density", 1.15D);
            }
            return plugin.getConfig().getDouble("generation.waterworld.island.density", 1.00D);
        }

        if (surfaceY >= seaLevel - 8) {
            return plugin.getConfig().getDouble("generation.waterworld.underwater-shelf.density", 0.90D);
        }
        if (surfaceY >= seaLevel - 28) {
            return plugin.getConfig().getDouble("generation.waterworld.ocean-floor.density", 0.75D);
        }
        return plugin.getConfig().getDouble("generation.waterworld.deep-ocean.density", 0.65D);
    }

    private boolean isLandSurface(Material material) {
        return material == Material.GRASS_BLOCK || material == Material.DIRT || material == Material.STONE
                || material == Material.SNOW_BLOCK || material == Material.SNOW;
    }

    private boolean isForbidden(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR
                || material == Material.WATER || material == Material.LAVA || material == Material.BUBBLE_COLUMN;
    }

    private List<OreProfile> loadProfiles(ConfigurationSection section, Environment env,
                                          int defaultMinSize, int defaultMaxSize, double globalDensity) {
        List<OreProfile> result = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection ore = section.getConfigurationSection(key);
            if (ore == null) continue;
            boolean enabled = ore.contains("enabled") ? ore.getBoolean("enabled", true) : ore.getBoolean("включено", true);
            if (!enabled) continue;
            try {
                Material material = Material.valueOf(key);
                if (env == Environment.NETHER ? material != Material.NETHERITE_SCRAP : material == Material.NETHERITE_SCRAP) continue;

                int weight = ore.contains("weight") ? ore.getInt("weight", 1) : ore.getInt("вес", 1);
                int minY = ore.contains("min-y") ? ore.getInt("min-y", -64) : ore.getInt("минимальный-y", -64);
                int maxY = ore.contains("max-y") ? ore.getInt("max-y", 96) : ore.getInt("максимальный-y", 96);
                int minSize = ore.contains("vein-size-min") ? ore.getInt("vein-size-min", defaultMinSize) : ore.getInt("размер-жилы-минимум", defaultMinSize);
                int maxSize = ore.contains("vein-size-max") ? ore.getInt("vein-size-max", defaultMaxSize) : ore.getInt("размер-жилы-максимум", defaultMaxSize);
                double oreDensity = ore.contains("density") ? ore.getDouble("density", 1.0D) : ore.getDouble("плотность", 1.0D);
                if (weight <= 0 || maxY < minY || oreDensity <= 0.0D) continue;
                result.add(new OreProfile(material, weight, minY, maxY,
                        Math.max(1, minSize), Math.max(Math.max(1, minSize), maxSize), oreDensity * globalDensity));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Неизвестный материал руды в конфигурации: " + key);
            }
        }
        return result;
    }

    private OreProfile rollProfile(List<OreProfile> profiles, SplittableRandom random, int y) {
        double total = 0.0D;
        for (OreProfile p : profiles) if (y >= p.minY && y <= p.maxY) total += p.weight * p.density;
        if (total <= 0.0D) return null;
        double roll = random.nextDouble(total);
        for (OreProfile p : profiles) {
            if (y < p.minY || y > p.maxY) continue;
            roll -= p.weight * p.density;
            if (roll <= 0.0D) return p;
        }
        return null;
    }

    private int placeVeinPart(World world, int targetCx, int targetCz,
                              int startX, int startY, int startZ,
                              Material ore, int size, int minY, int maxY,
                              Environment environment, SplittableRandom random) {
        List<long[]> vein = new ArrayList<>(size);
        vein.add(new long[]{startX, startY, startZ});
        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        for (int i = 1; i < size; i++) {
            long[] base = vein.get(random.nextInt(vein.size()));
            int[] direction = directions[random.nextInt(directions.length)];
            int x = (int) base[0] + direction[0];
            int y = (int) base[1] + direction[1];
            int z = (int) base[2] + direction[2];
            if (y < minY || y > maxY || contains(vein, x, y, z)) continue;
            vein.add(new long[]{x, y, z});
        }

        int placed = 0;
        for (long[] pos : vein) {
            int x = (int) pos[0], y = (int) pos[1], z = (int) pos[2];
            if ((x >> 4) != targetCx || (z >> 4) != targetCz) continue;
            if (!world.isChunkLoaded(targetCx, targetCz)) continue;

            Block block = world.getBlockAt(x, y, z);
            if (!isValidHost(block.getType(), environment)) continue;
            if (nodeManager.isNode(world.getUID(), x, y, z)) continue;

            nodeManager.addNode(new Location(world, x, y, z), ore, nodeManager.randomHits());
            placed++;
        }
        return placed;
    }

    private boolean isValidHost(Material material, Environment environment) {
        if (isForbidden(material)) return false;
        if (environment == Environment.NETHER) {
            return material == Material.NETHERRACK || material == Material.BASALT || material == Material.BLACKSTONE;
        }

        return material == Material.STONE || material == Material.DEEPSLATE || material == Material.TUFF
                || material == Material.ANDESITE || material == Material.DIORITE || material == Material.GRANITE
                || material == Material.SAND || material == Material.RED_SAND || material == Material.GRAVEL;
    }

    private boolean isAllowedOreMaterial(Material material) {
        return material == Material.DEEPSLATE_COAL_ORE || material == Material.DEEPSLATE_IRON_ORE
                || material == Material.DEEPSLATE_COPPER_ORE || material == Material.DEEPSLATE_GOLD_ORE
                || material == Material.DEEPSLATE_REDSTONE_ORE || material == Material.DEEPSLATE_LAPIS_ORE
                || material == Material.DEEPSLATE_DIAMOND_ORE || material == Material.DEEPSLATE_EMERALD_ORE
                || material == Material.NETHERITE_SCRAP;
    }

    private boolean contains(List<long[]> positions, int x, int y, int z) {
        for (long[] p : positions) if ((int) p[0] == x && (int) p[1] == y && (int) p[2] == z) return true;
        return false;
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

    private int getInt(String modern, String legacy, int def) {
        return plugin.getConfig().contains(modern) ? plugin.getConfig().getInt(modern, def) : plugin.getConfig().getInt(legacy, def);
    }

    private double getDouble(String modern, String legacy, double def) {
        return plugin.getConfig().contains(modern) ? plugin.getConfig().getDouble(modern, def) : plugin.getConfig().getDouble(legacy, def);
    }

    private static long mixSeed(long seed, int cx, int cz) {
        long s = seed ^ ((long) cx * 0x9E3779B97F4A7C15L) ^ ((long) cz * 0xC2B2AE3D27D4EB4FL);
        s ^= s >>> 30;
        s *= 0xBF58476D1CE4E5B9L;
        s ^= s >>> 27;
        s *= 0x94D049BB133111EBL;
        return s ^ (s >>> 31);
    }

    private record OreProfile(Material material, int weight, int minY, int maxY,
                              int minSize, int maxSize, double density) {}
}
