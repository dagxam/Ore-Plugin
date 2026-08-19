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
 * Генератор рудных узлов.
 *
 * Генерация детерминированная: seed мира + координаты чанка дают стабильный
 * результат. В обычном мире дополнительно определяется слой местности:
 * остров, океан или глубокий океан. Множитель слоя применяется к плотности.
 *
 * Команды, permission и Bukkit Material остаются английскими.
 * Пользовательские настройки и технические сообщения — русские.
 */
public class GenerationListener implements Listener {
    private static final long GENERATOR_SALT = 0x4F524556325F4F52L;

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
            try {
                queueTask.cancel();
            } catch (Throwable ignored) {
            }
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

    /** Генерирует рудные узлы в загруженном чанке. */
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

        long seed = mixSeed(world.getSeed() ^ GENERATOR_SALT, targetChunk.getX(), targetChunk.getZ());
        SplittableRandom random = new SplittableRandom(seed);
        List<long[]> centers = new ArrayList<>();

        int placedCenters = 0;
        int inspected = 0;
        int maxAttempts = Math.min(attempts, 10000);

        int minProfileY = minY(profiles);
        int maxProfileY = maxY(profiles);
        if (maxProfileY < minProfileY) return;

        while (inspected++ < maxAttempts && placedCenters < maxNodes) {
            int x = (targetChunk.getX() << 4) + random.nextInt(16);
            int z = (targetChunk.getZ() << 4) + random.nextInt(16);
            int y = minProfileY + random.nextInt(maxProfileY - minProfileY + 1);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!isReplaceable(world.getBlockAt(x, y, z).getType(), environment)) continue;
            if (!farEnough(centers, x, y, z, spacing)) continue;

            OreProfile profile = rollProfile(profiles, random, y);
            if (profile == null) continue;

            double layerDensity = environment == Environment.NORMAL ? layerDensity(world, x, z) :
                    plugin.getConfig().getDouble("генерация.слои.ад.пещеры", 1.0D);
            if (layerDensity <= 0.0D) continue;

            double chance = Math.min(1.0D, baseChance * profile.density * layerDensity);
            if (placedCenters >= targetNodes && random.nextDouble() > chance * 8.0D) continue;
            if (placedCenters < targetNodes && random.nextDouble() > chance * 16.0D) continue;

            int size = profile.minSize + random.nextInt(profile.maxSize - profile.minSize + 1);
            int placed = placeVein(world, x, y, z, profile.material, size, profile.minY, profile.maxY, environment, random);
            if (placed > 0) {
                centers.add(new long[]{x, y, z});
                placedCenters++;
            }
        }
    }

    /**
     * Определяет слой по поверхности, а не по Y руды.
     * Поэтому одна и та же подземная область получает правильный множитель
     * независимо от того, находится она под островом или под океаном.
     */
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

        // На поверхности вода означает океан. Если высшая точка заметно ниже уровня моря,
        // считаем это глубоким океаном. Сухая поверхность выше уровня моря — остров.
        if (surface == Material.WATER || surface == Material.KELP || surface == Material.KELP_PLANT
                || surfaceY <= seaLevel - 12) {
            if (surfaceY <= seaLevel - 12) {
                return Math.max(0.0D, layers.getDouble("глубокий-океан", 0.70D));
            }
            return Math.max(0.0D, layers.getDouble("океан", 0.85D));
        }

        return Math.max(0.0D, layers.getDouble("остров", 1.00D));
    }

    private List<OreProfile> loadProfiles(ConfigurationSection ores,
                                          Environment environment,
                                          int defaultMinSize,
                                          int defaultMaxSize,
                                          double globalDensity) {
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

            if (!isAllowedOreMaterial(material)) {
                plugin.getLogger().warning("Материал запрещён для генерации руды: " + key);
                continue;
            }

            if (material == Material.NETHERITE_SCRAP && environment != Environment.NETHER) continue;
            if (material != Material.NETHERITE_SCRAP && environment == Environment.NETHER && key.startsWith("DEEPSLATE_")) continue;

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

    private int placeVein(World world, int startX, int startY, int startZ, Material ore,
                           int size, int minY, int maxY, Environment environment, SplittableRandom random) {
        List<long[]> vein = new ArrayList<>(size);
        vein.add(new long[]{startX, startY, startZ});

        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int i = 1; i < size; i++) {
            long[] base = vein.get(random.nextInt(vein.size()));
            int[] direction = directions[random.nextInt(directions.length)];
            int x = (int) base[0] + direction[0];
            int y = (int) base[1] + direction[1];
            int z = (int) base[2] + direction[2];
            if (y < minY || y > maxY) continue;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!isReplaceable(world.getBlockAt(x, y, z).getType(), environment)) continue;
            if (contains(vein, x, y, z)) continue;
            vein.add(new long[]{x, y, z});
        }

        int placed = 0;
        for (long[] pos : vein) {
            int x = (int) pos[0], y = (int) pos[1], z = (int) pos[2];
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!isReplaceable(world.getBlockAt(x, y, z).getType(), environment)) continue;
            if (nodeManager.isNode(world.getUID(), x, y, z)) continue;

            nodeManager.addNode(new Location(world, x, y, z), ore, nodeManager.randomHits());
            placed++;
        }
        return placed;
    }

    private boolean contains(List<long[]> positions, int x, int y, int z) {
        for (long[] p : positions) if ((int)p[0] == x && (int)p[1] == y && (int)p[2] == z) return true;
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

    private boolean isReplaceable(Material material, Environment environment) {
        if (environment == Environment.NETHER) {
            return material == Material.NETHERRACK || material == Material.BASALT || material == Material.BLACKSTONE;
        }

        if (material == Material.STONE || material == Material.DEEPSLATE || material == Material.TUFF) return true;
        if (!plugin.getConfig().getBoolean("генерация.обычный-мир.разрешить-варианты-камня", true)) return false;
        String name = material.name();
        return name.equals("ANDESITE") || name.equals("DIORITE") || name.equals("GRANITE");
    }

    private boolean isAllowedOreMaterial(Material material) {
        return material == Material.ANCIENT_DEBRIS || material == Material.NETHERITE_SCRAP || material.name().endsWith("_ORE");
    }

    private long mixSeed(long seed, int x, int z) {
        long value = seed;
        value ^= ((long)x * 0x9E3779B97F4A7C15L);
        value ^= ((long)z * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private static final class OreProfile {
        final Material material;
        final int weight;
        final int minSize;
        final int maxSize;
        final int minY;
        final int maxY;
        final double density;

        OreProfile(Material material, int weight, int minSize, int maxSize, int minY, int maxY, double density) {
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
