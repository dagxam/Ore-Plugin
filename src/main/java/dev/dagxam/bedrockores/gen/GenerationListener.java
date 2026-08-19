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
 * Детерминированный генератор рудных узлов.
 *
 * Жила определяется не только текущим чанком, а расширенной областью 3x3.
 * Поэтому одна и та же жила может физически пересекать границу чанков.
 * Каждый чанк при загрузке достраивает только свою часть уже определённых жил.
 * Соседние чанки принудительно не загружаются.
 */
public class GenerationListener implements Listener {
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
        ConfigurationSection queue = plugin.getConfig().getConfigurationSection("генерация.очередь");
        queueEnabled = queue != null && queue.getBoolean("включено", true);
        chunksPerTick = Math.max(1, queue == null ? 2 : queue.getInt("чанков-за-тик", 2));
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

            Chunk chunk = world.getChunkAt(cx, cz);
            if (!nodeManager.isChunkProcessed(world, cx, cz)) {
                generateInChunk(chunk);
                nodeManager.markChunkProcessed(world, cx, cz);
            }
            nodeManager.processDueRespawnsInChunk(chunk);
        }
    }

    private static String chunkKey(UUID world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
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
     * Генерирует только часть детерминированных жил, принадлежащую текущему чанку.
     * Центры берутся из области 3x3 чанков, поэтому жила может пересекать границу.
     */
    public void generateInChunk(Chunk targetChunk) {
        if (targetChunk == null) return;
        World world = targetChunk.getWorld();
        Environment environment = world.getEnvironment();
        if (environment != Environment.NORMAL && environment != Environment.NETHER) return;

        ConfigurationSection ores = plugin.getConfig().getConfigurationSection("генерация.руды");
        if (ores == null) return;

        int maxNodes = Math.max(0, plugin.getConfig().getInt("генерация.максимум-узлов-на-чанк", 24));
        int targetNodes = Math.max(0, plugin.getConfig().getInt("генерация.узлов-на-чанк", 12));
        int attempts = Math.max(100, plugin.getConfig().getInt("генерация.максимум-попыток-на-чанк", 1800));
        int defaultMinSize = Math.max(1, plugin.getConfig().getInt("генерация.жила.размер-минимум", 3));
        int defaultMaxSize = Math.max(defaultMinSize, plugin.getConfig().getInt("генерация.жила.размер-максимум", 6));
        int spacing = Math.max(1, plugin.getConfig().getInt("генерация.жила.минимальная-дистанция", 4));
        double globalDensity = Math.max(0.0D, plugin.getConfig().getDouble("генерация.общий-множитель-плотности", 1.0D));
        double baseChance = Math.max(0.0D, plugin.getConfig().getDouble("генерация.шанс-на-блок", 0.008D));

        if (maxNodes <= 0 || attempts <= 0) return;

        List<OreProfile> profiles = loadProfiles(ores, environment, defaultMinSize, defaultMaxSize, globalDensity);
        if (profiles.isEmpty()) return;

        int targetCx = targetChunk.getX();
        int targetCz = targetChunk.getZ();
        int minProfileY = minY(profiles);
        int maxProfileY = maxY(profiles);
        if (maxProfileY < minProfileY) return;

        // Кандидатные центры принадлежат конкретному чанку по его координатному seed.
        // Поэтому при генерации соседнего чанка мы получим абсолютно те же центры.
        int placedInTarget = 0;
        int processedCenters = 0;
        Set<Long> localCenters = new HashSet<>();

        for (int centerCx = targetCx - VEIN_SEARCH_RADIUS_CHUNKS;
             centerCx <= targetCx + VEIN_SEARCH_RADIUS_CHUNKS;
             centerCx++) {
            for (int centerCz = targetCz - VEIN_SEARCH_RADIUS_CHUNKS;
                 centerCz <= targetCz + VEIN_SEARCH_RADIUS_CHUNKS;
                 centerCz++) {

                long seed = mixSeed(world.getSeed() ^ GENERATOR_SALT, centerCx, centerCz);
                SplittableRandom random = new SplittableRandom(seed);
                int centerAttempts = Math.max(1, attempts / 9);

                for (int i = 0; i < centerAttempts; i++) {
                    if (processedCenters >= maxNodes * 9) break;

                    int x = (centerCx << 4) + random.nextInt(16);
                    int z = (centerCz << 4) + random.nextInt(16);
                    int y = minProfileY + random.nextInt(maxProfileY - minProfileY + 1);

                    long centerKey = packPosition(x, y, z);
                    if (!localCenters.add(centerKey)) continue;
                    if (!isChunkInTargetNeighborhood(x >> 4, z >> 4, targetCx, targetCz)) continue;
                    if (!isReplaceable(world.getBlockAt(x, y, z).getType(), environment)) continue;

                    OreProfile profile = rollProfile(profiles, random, y);
                    if (profile == null) continue;

                    double layerDensity = environment == Environment.NORMAL
                            ? layerDensity(world, x, z)
                            : plugin.getConfig().getDouble("генерация.слои.ад.пещеры", 1.0D);
                    if (layerDensity <= 0.0D) continue;

                    double chance = Math.min(1.0D, baseChance * profile.density * layerDensity);
                    if (random.nextDouble() > chance * 16.0D) continue;

                    int size = profile.minSize + random.nextInt(profile.maxSize - profile.minSize + 1);
                    int placed = placeVeinPart(
                            world, targetCx, targetCz,
                            x, y, z, profile.material, size,
                            profile.minY, profile.maxY, environment, random
                    );
                    if (placed > 0) placedInTarget += placed;
                    processedCenters++;

                    // Ограничиваем только фактическое количество узлов текущего чанка.
                    if (placedInTarget >= maxNodes) return;
                }
            }
        }

        // Если детерминированная выборка дала мало жил, делаем небольшой локальный добор.
        // Он также детерминирован и поэтому не меняется между загрузками мира.
        if (placedInTarget < targetNodes) {
            topUpChunk(world, targetCx, targetCz, profiles, environment,
                    minProfileY, maxProfileY, spacing, baseChance, targetNodes, maxNodes);
        }
    }

    private void topUpChunk(World world, int cx, int cz, List<OreProfile> profiles,
                            Environment environment, int minY, int maxY, int spacing,
                            double baseChance, int targetNodes, int maxNodes) {
        long seed = mixSeed(world.getSeed() ^ GENERATOR_SALT ^ 0x5455504C5F55505AL, cx, cz);
        SplittableRandom random = new SplittableRandom(seed);
        List<long[]> centers = new ArrayList<>();
        int placed = 0;
        int attempts = Math.min(600, Math.max(100, targetNodes * 40));

        for (int i = 0; i < attempts && placed < maxNodes; i++) {
            int x = (cx << 4) + random.nextInt(16);
            int z = (cz << 4) + random.nextInt(16);
            int y = minY + random.nextInt(maxY - minY + 1);
            if (!isReplaceable(world.getBlockAt(x, y, z).getType(), environment)) continue;
            if (!farEnough(centers, x, y, z, spacing)) continue;

            OreProfile profile = rollProfile(profiles, random, y);
            if (profile == null) continue;
            double density = environment == Environment.NORMAL ? layerDensity(world, x, z)
                    : plugin.getConfig().getDouble("генерация.слои.ад.пещеры", 1.0D);
            if (random.nextDouble() > Math.min(1.0D, baseChance * profile.density * density * 16.0D)) continue;

            int size = profile.minSize + random.nextInt(profile.maxSize - profile.minSize + 1);
            int before = countNodesInTargetChunk(world, cx, cz, x, y, z, profile, size, environment, random);
            if (before > 0) {
                centers.add(new long[]{x, y, z});
                placed += before;
            }
        }
    }

    private int countNodesInTargetChunk(World world, int targetCx, int targetCz,
                                        int x, int y, int z, OreProfile profile, int size,
                                        Environment environment, SplittableRandom random) {
        List<long[]> positions = buildVeinPositions(world, x, y, z, size, profile.minY, profile.maxY, environment, random);
        int placed = 0;
        for (long[] p : positions) {
            int px = (int) p[0], py = (int) p[1], pz = (int) p[2];
            if ((px >> 4) != targetCx || (pz >> 4) != targetCz) continue;
            if (!world.isChunkLoaded(targetCx, targetCz)) continue;
            if (!isReplaceable(world.getBlockAt(px, py, pz).getType(), environment)) continue;
            if (nodeManager.isNode(world.getUID(), px, py, pz)) continue;
            nodeManager.addNode(new Location(world, px, py, pz), profile.material, nodeManager.randomHits());
            placed++;
        }
        return placed;
    }

    private int placeVeinPart(World world, int targetCx, int targetCz,
                              int startX, int startY, int startZ, Material ore,
                              int size, int minY, int maxY, Environment environment,
                              SplittableRandom random) {
        OreProfile profile = new OreProfile(ore, 1, 1, size, minY, maxY, 1.0D);
        List<long[]> vein = buildVeinPositions(world, startX, startY, startZ, size, minY, maxY, environment, random);
        int placed = 0;

        for (long[] pos : vein) {
            int x = (int) pos[0], y = (int) pos[1], z = (int) pos[2];
            if ((x >> 4) != targetCx || (z >> 4) != targetCz) continue;
            if (!world.isChunkLoaded(targetCx, targetCz)) continue;
            if (!isReplaceable(world.getBlockAt(x, y, z).getType(), environment)) continue;
            if (nodeManager.isNode(world.getUID(), x, y, z)) continue;

            nodeManager.addNode(new Location(world, x, y, z), profile.material, nodeManager.randomHits());
            placed++;
        }
        return placed;
    }

    /** Строит детерминированную геометрию жилы. Незагруженные чанки не загружаются. */
    private List<long[]> buildVeinPositions(World world, int startX, int startY, int startZ,
                                            int size, int minY, int maxY,
                                            Environment environment, SplittableRandom random) {
        List<long[]> vein = new ArrayList<>(size);
        vein.add(new long[]{startX, startY, startZ});
        int[][] directions = {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
        };

        for (int i = 1; i < size; i++) {
            long[] base = vein.get(random.nextInt(vein.size()));
            int[] direction = directions[random.nextInt(directions.length)];
            int x = (int) base[0] + direction[0];
            int y = (int) base[1] + direction[1];
            int z = (int) base[2] + direction[2];

            if (y < minY || y > maxY) continue;
            if (contains(vein, x, y, z)) continue;

            // Важно: если соседний чанк не загружен, мы всё равно сохраняем
            // координату в детерминированной жиле. Когда тот чанк загрузится,
            // он сам поставит свою часть жилы.
            if (world.isChunkLoaded(x >> 4, z >> 4)) {
                if (!isReplaceable(world.getBlockAt(x, y, z).getType(), environment)) continue;
            }
            vein.add(new long[]{x, y, z});
        }
        return vein;
    }

    private double layerDensity(World world, int x, int z) {
        ConfigurationSection layers = plugin.getConfig().getConfigurationSection("генерация.слои.обычный-мир");
        if (layers == null || !layers.getBoolean("включено", true)) return 1.0D;

        int seaLevel = world.getSeaLevel();
        int surfaceY;
        Material surface;
        try {
            surfaceY = world.getHighestBlockYAt(x, z);
            surface = world.getBlockAt(x, surfaceY, z).getType();
        } catch (RuntimeException ex) {
            return 1.0D;
        }

        if (surface == Material.WATER || surface == Material.KELP || surface == Material.KELP_PLANT
                || surfaceY <= seaLevel - 12) {
            return Math.max(0.0D, layers.getDouble(
                    surfaceY <= seaLevel - 12 ? "глубокий-океан" : "океан", 0.70D));
        }
        return Math.max(0.0D, layers.getDouble("остров", 1.00D));
    }

    private List<OreProfile> loadProfiles(ConfigurationSection ores, Environment environment,
                                          int defaultMinSize, int defaultMaxSize, double globalDensity) {
        List<OreProfile> profiles = new ArrayList<>();

        for (String key : ores.getKeys(false)) {
            ConfigurationSection section = ores.getConfigurationSection(key);
            if (section == null || !section.getBoolean("включено", true)) continue;

            Material material;
            try {
                material = Material.valueOf(key);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Неизвестный материал в настройках руды: " + key);
                continue;
            }

            if (!isAllowedOreMaterial(material)) continue;
            if (material == Material.NETHERITE_SCRAP && environment != Environment.NETHER) continue;
            if (material != Material.NETHERITE_SCRAP && environment == Environment.NETHER
                    && key.startsWith("DEEPSLATE_")) continue;

            int weight = Math.max(0, section.getInt("вес", 1));
            int minSize = Math.max(1, section.getInt("размер-жилы-минимум", defaultMinSize));
            int maxSize = Math.max(minSize, section.getInt("размер-жилы-максимум", defaultMaxSize));
            int minY = section.getInt("минимальный-y", environment == Environment.NETHER ? 0 : -64);
            int maxY = section.getInt("максимальный-y", environment == Environment.NETHER ? 32 : 63);
            double density = Math.max(0.0D, section.getDouble("плотность", 1.0D) * globalDensity);

            if (weight <= 0 || maxY < minY || density <= 0.0D) continue;
            profiles.add(new OreProfile(material, weight, minSize, maxSize, minY, maxY, density));
        }

        if (profiles.isEmpty()) {
            String sectionName = environment == Environment.NETHER ? "веса-руд-ада" : "веса-руд";
            ConfigurationSection legacy = plugin.getConfig().getConfigurationSection(sectionName);
            if (legacy != null) {
                for (String key : legacy.getKeys(false)) {
                    try {
                        Material material = Material.valueOf(key);
                        if (!isAllowedOreMaterial(material)) continue;
                        if (material == Material.NETHERITE_SCRAP && environment != Environment.NETHER) continue;
                        if (environment == Environment.NETHER && material.name().startsWith("DEEPSLATE_")) continue;
                        int weight = legacy.getInt(key);
                        if (weight > 0) {
                            profiles.add(new OreProfile(material, weight, defaultMinSize, defaultMaxSize,
                                    environment == Environment.NETHER ? 0 : -64,
                                    environment == Environment.NETHER ? 32 : 63,
                                    globalDensity));
                        }
                    } catch (IllegalArgumentException ignored) {
                        plugin.getLogger().warning("Неизвестная руда в разделе " + sectionName + ": " + key);
                    }
                }
            }
        }
        return profiles;
    }

    private int minY(List<OreProfile> profiles) {
        int result = Integer.MAX_VALUE;
        for (OreProfile p : profiles) result = Math.min(result, p.minY);
        return result;
    }

    private int maxY(List<OreProfile> profiles) {
        int result = Integer.MIN_VALUE;
        for (OreProfile p : profiles) result = Math.max(result, p.maxY);
        return result;
    }

    private OreProfile rollProfile(List<OreProfile> profiles, SplittableRandom random, int y) {
        int total = 0;
        for (OreProfile p : profiles) if (y >= p.minY && y <= p.maxY) total += p.weight;
        if (total <= 0) return null;

        int roll = random.nextInt(total);
        for (OreProfile p : profiles) {
            if (y < p.minY || y > p.maxY) continue;
            roll -= p.weight;
            if (roll < 0) return p;
        }
        return null;
    }

    private boolean contains(List<long[]> positions, int x, int y, int z) {
        for (long[] p : positions) {
            if ((int) p[0] == x && (int) p[1] == y && (int) p[2] == z) return true;
        }
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

    /** Воздух, вода и лава всегда запрещены. Песок и гравий разрешены. */
    private boolean isReplaceable(Material material, Environment environment) {
        if (material.isAir() || material == Material.WATER || material == Material.LAVA
                || material == Material.BUBBLE_COLUMN) return false;

        if (environment == Environment.NETHER) {
            return material == Material.NETHERRACK
                    || material == Material.BASALT
                    || material == Material.BLACKSTONE;
        }

        if (material == Material.STONE || material == Material.DEEPSLATE || material == Material.TUFF
                || material == Material.SAND || material == Material.RED_SAND || material == Material.GRAVEL) {
            return true;
        }

        if (!plugin.getConfig().getBoolean("генерация.обычный-мир.разрешить-варианты-камня", true)) return false;
        String name = material.name();
        return name.equals("ANDESITE") || name.equals("DIORITE") || name.equals("GRANITE");
    }

    private boolean isAllowedOreMaterial(Material material) {
        if (material == Material.NETHERITE_SCRAP) return true;
        String name = material.name();
        return name.endsWith("_ORE") && (name.startsWith("DEEPSLATE_") || name.equals("ANCIENT_DEBRIS"));
    }

    private long mixSeed(long seed, int chunkX, int chunkZ) {
        long x = seed ^ ((long) chunkX * 0x9E3779B97F4A7C15L);
        long z = ((long) chunkZ * 0xC2B2AE3D27D4EB4FL);
        long mixed = x ^ z;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private long packPosition(int x, int y, int z) {
        long a = ((long) x & 0x3FFFFFFL) << 38;
        long b = ((long) z & 0x3FFFFFFL) << 12;
        long c = y & 0xFFFL;
        return a | b | c;
    }

    private boolean isChunkInTargetNeighborhood(int cx, int cz, int targetCx, int targetCz) {
        return Math.abs(cx - targetCx) <= VEIN_SEARCH_RADIUS_CHUNKS
                && Math.abs(cz - targetCz) <= VEIN_SEARCH_RADIUS_CHUNKS;
    }

    private static final class OreProfile {
        final Material material;
        final int weight;
        final int minSize;
        final int maxSize;
        final int minY;
        final int maxY;
        final double density;

        OreProfile(Material material, int weight, int minSize, int maxSize,
                   int minY, int maxY, double density) {
            this.material = material;
            this.weight = weight;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.minY = minY;
            this.maxY = maxY;
            this.density = density;
        }
    }
}
