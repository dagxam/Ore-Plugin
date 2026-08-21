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
 * Единый генератор руд для WaterWorld.
 * Сначала генерируются обычные ванильные руды, затем особые многоразовые узлы.
 */
public final class GenerationListener implements Listener {
    private static final long VANILLA_SALT = 0x56414E494C4C4141L;
    private static final long NODE_SALT = 0x4E4F44455F4F5245L;
    private static final int VEIN_SEARCH_RADIUS_CHUNKS = 1;

    private final Plugin plugin;
    private final NodeManager nodeManager;
    private boolean queueEnabled;
    private int chunksPerTick;
    private BukkitTask queueTask;
    private final ArrayDeque<ChunkRef> chunkQueue = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>();

    public GenerationListener(Plugin plugin, NodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
        reloadSettings();
    }

    public void reloadSettings() {
        ConfigurationSection queue = plugin.getConfig().getConfigurationSection("generation.queue");
        if (queue == null) queue = plugin.getConfig().getConfigurationSection("генерация.очередь");
        queueEnabled = queue == null || queue.getBoolean(queue.contains("enabled") ? "enabled" : "включено", true);
        chunksPerTick = Math.max(1, queue == null ? 2 : queue.getInt(queue.contains("chunks-per-tick") ? "chunks-per-tick" : "чанков-за-тик", 2));
        stopQueue();
        startQueueIfEnabled();
    }

    public void startQueueIfEnabled() {
        if (!queueEnabled || queueTask != null) return;
        queueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainQueue, 1L, 1L);
        plugin.getLogger().info("Очередь генерации руд запущена. Чанков за тик: " + chunksPerTick);
    }

    public void stopQueue() {
        if (queueTask != null) {
            queueTask.cancel();
            queueTask = null;
        }
        chunkQueue.clear();
        queuedKeys.clear();
    }

    private boolean isEnabledWorld(World world) {
        if (world == null) return false;
        List<String> worlds = plugin.getConfig().getStringList("разрешённые-мирами");
        if (worlds.isEmpty()) worlds = plugin.getConfig().getStringList("enabled-worlds");
        return worlds.contains(world.getName());
    }

    private boolean isWaterWorld(World world) {
        if (world == null || world.getEnvironment() != Environment.NORMAL) return false;
        if (!plugin.getConfig().getBoolean("integrations.waterworld.включено", true)) return false;
        String configured = plugin.getConfig().getString("integrations.waterworld.имя-мира", "");
        return configured == null || configured.isBlank() || configured.equals(world.getName());
    }

    private void offerChunk(Chunk chunk) {
        String key = chunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (!queuedKeys.add(key)) return;
        chunkQueue.add(new ChunkRef(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    private void drainQueue() {
        int budget = chunksPerTick;
        while (budget-- > 0 && !chunkQueue.isEmpty()) {
            ChunkRef ref = chunkQueue.poll();
            queuedKeys.remove(chunkKey(ref.worldId, ref.chunkX, ref.chunkZ));
            World world = Bukkit.getWorld(ref.worldId);
            if (world == null || !isEnabledWorld(world) || !world.isChunkLoaded(ref.chunkX, ref.chunkZ)) continue;
            processLoadedChunk(world.getChunkAt(ref.chunkX, ref.chunkZ));
        }
    }

    private void processLoadedChunk(Chunk chunk) {
        World world = chunk.getWorld();
        nodeManager.loadChunkAsync(chunk, () -> {
            if (!world.isChunkLoaded(chunk.getX(), chunk.getZ())) return;
            if (!nodeManager.isChunkProcessed(world, chunk.getX(), chunk.getZ())) {
                generateVanillaOresInChunk(chunk);
                generateSpecialNodesInChunk(chunk);
                nodeManager.markChunkProcessed(world, chunk.getX(), chunk.getZ());
            }
            nodeManager.processDueRespawnsInChunk(chunk);
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!isEnabledWorld(event.getWorld())) return;
        if (queueEnabled) offerChunk(event.getChunk());
        else processLoadedChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        nodeManager.unloadChunk(event.getChunk());
    }

    /** Обычные руды: не попадают в NodeManager и ломаются как ванильные блоки. */
    private void generateVanillaOresInChunk(Chunk targetChunk) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("generation.vanilla-ores");
        if (root == null || !root.getBoolean("enabled", true)) return;
        ConfigurationSection ores = root.getConfigurationSection("ores");
        if (ores == null) return;

        World world = targetChunk.getWorld();
        Environment environment = world.getEnvironment();
        List<OreProfile> profiles = loadProfiles(ores, environment);
        if (profiles.isEmpty()) return;

        int maxVeins = Math.max(0, root.getInt("max-veins-per-chunk", 18));
        int attempts = Math.max(0, root.getInt("max-attempts-per-chunk", 220));
        int minY = minY(profiles, world.getMinHeight());
        int maxY = maxY(profiles, world.getMaxHeight() - 1);
        if (maxVeins == 0 || attempts == 0 || maxY < minY) return;

        SplittableRandom random = new SplittableRandom(mixSeed(world.getSeed() ^ VANILLA_SALT, targetChunk.getX(), targetChunk.getZ()));
        int placedVeins = 0;
        for (int attempt = 0; attempt < attempts && placedVeins < maxVeins; attempt++) {
            int x = (targetChunk.getX() << 4) + random.nextInt(16);
            int z = (targetChunk.getZ() << 4) + random.nextInt(16);
            int y = minY + random.nextInt(maxY - minY + 1);
            OreProfile profile = rollProfile(profiles, random, y);
            if (profile == null || random.nextDouble() > profile.density) continue;
            int size = profile.minSize + random.nextInt(profile.maxSize - profile.minSize + 1);
            if (placeVanillaVein(world, targetChunk.getX(), targetChunk.getZ(), x, y, z, profile, size, environment, random) > 0) {
                placedVeins++;
            }
        }
    }

    /** Особые узлы: сохраняются в SQLite и добываются многократно. */
    public void generateSpecialNodesInChunk(Chunk targetChunk) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("generation.special-nodes");
        if (root == null) root = plugin.getConfig().getConfigurationSection("generation");
        if (!root.getBoolean(root.contains("enabled") ? "enabled" : "включено", true)) return;
        ConfigurationSection ores = root.getConfigurationSection("ores");
        if (ores == null) ores = plugin.getConfig().getConfigurationSection("generation.ores");
        if (ores == null) return;

        World world = targetChunk.getWorld();
        Environment environment = world.getEnvironment();
        List<OreProfile> profiles = loadProfiles(ores, environment);
        if (profiles.isEmpty()) return;

        int maxNodes = Math.max(0, root.getInt("max-per-chunk", getInt("generation.max-per-chunk", "генерация.максимум-узлов-на-чанк", 8)));
        int attempts = Math.max(0, root.getInt("max-attempts-per-chunk", getInt("generation.max-attempts-per-chunk", "генерация.максимум-попыток-на-чанк", 900)));
        int spacing = Math.max(1, root.getInt("min-spacing", getInt("generation.cluster.min-spacing", "генерация.жила.минимальная-дистанция", 6)));
        int minY = minY(profiles, world.getMinHeight());
        int maxY = maxY(profiles, world.getMaxHeight() - 1);
        if (maxNodes == 0 || attempts == 0 || maxY < minY) return;

        List<long[]> centers = new ArrayList<>();
        int placed = 0;
        int inspected = 0;
        for (int centerCx = targetChunk.getX() - VEIN_SEARCH_RADIUS_CHUNKS; centerCx <= targetChunk.getX() + VEIN_SEARCH_RADIUS_CHUNKS; centerCx++) {
            for (int centerCz = targetChunk.getZ() - VEIN_SEARCH_RADIUS_CHUNKS; centerCz <= targetChunk.getZ() + VEIN_SEARCH_RADIUS_CHUNKS; centerCz++) {
                SplittableRandom random = new SplittableRandom(mixSeed(world.getSeed() ^ NODE_SALT, centerCx, centerCz));
                int candidates = centerCx == targetChunk.getX() && centerCz == targetChunk.getZ() ? 4 : 1;
                for (int i = 0; i < candidates && inspected++ < attempts && placed < maxNodes; i++) {
                    int x = (centerCx << 4) + random.nextInt(16);
                    int z = (centerCz << 4) + random.nextInt(16);
                    int y = minY + random.nextInt(maxY - minY + 1);
                    OreProfile profile = rollProfile(profiles, random, y);
                    if (profile == null || random.nextDouble() > profile.density * getWaterWorldDensity(world, x, y, z)) continue;
                    if (!farEnough(centers, x, y, z, spacing)) continue;
                    int size = profile.minSize + random.nextInt(profile.maxSize - profile.minSize + 1);
                    int changed = placeSpecialVeinPart(world, targetChunk.getX(), targetChunk.getZ(), x, y, z, profile, size, environment, random);
                    if (changed > 0) {
                        centers.add(new long[]{x, y, z});
                        placed += changed;
                    }
                }
            }
        }
    }

    /** Совместимость со старым публичным вызовом. */
    public void generateInChunk(Chunk chunk) {
        generateSpecialNodesInChunk(chunk);
    }

    private int placeVanillaVein(World world, int targetCx, int targetCz, int startX, int startY, int startZ,
                                 OreProfile profile, int size, Environment environment, SplittableRandom random) {
        int placed = 0;
        for (long[] pos : buildVein(startX, startY, startZ, size, profile.minY, profile.maxY, random)) {
            int x = (int) pos[0], y = (int) pos[1], z = (int) pos[2];
            if ((x >> 4) != targetCx || (z >> 4) != targetCz) continue;
            Block block = world.getBlockAt(x, y, z);
            if (!isValidHost(block.getType(), environment)) continue;
            block.setType(resolvePlacedMaterial(profile.material, block.getType()), false);
            placed++;
        }
        return placed;
    }

    private int placeSpecialVeinPart(World world, int targetCx, int targetCz, int startX, int startY, int startZ,
                                     OreProfile profile, int size, Environment environment, SplittableRandom random) {
        int placed = 0;
        for (long[] pos : buildVein(startX, startY, startZ, size, profile.minY, profile.maxY, random)) {
            int x = (int) pos[0], y = (int) pos[1], z = (int) pos[2];
            if ((x >> 4) != targetCx || (z >> 4) != targetCz) continue;
            Block block = world.getBlockAt(x, y, z);
            if (!isValidHost(block.getType(), environment) || nodeManager.isNode(world.getUID(), x, y, z)) continue;
            nodeManager.addNode(new Location(world, x, y, z), profile.material, nodeManager.randomHits());
            placed++;
        }
        return placed;
    }

    private List<long[]> buildVein(int startX, int startY, int startZ, int size, int minY, int maxY, SplittableRandom random) {
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
        return vein;
    }

    private Material resolvePlacedMaterial(Material configured, Material host) {
        if (configured == Material.NETHERITE_SCRAP) return Material.ANCIENT_DEBRIS;
        if (host == Material.DEEPSLATE) {
            String name = configured.name();
            if (name.endsWith("_ORE") && !name.startsWith("DEEPSLATE_")) {
                try { return Material.valueOf("DEEPSLATE_" + name); } catch (IllegalArgumentException ignored) { }
            }
        }
        if (configured.name().startsWith("DEEPSLATE_") && host != Material.DEEPSLATE) {
            String normal = configured.name().substring("DEEPSLATE_".length());
            try { return Material.valueOf(normal); } catch (IllegalArgumentException ignored) { }
        }
        return configured;
    }

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
            return plugin.getConfig().getDouble("generation.waterworld.island.density", 1.0D);
        }
        if (surfaceY >= seaLevel - 8) return plugin.getConfig().getDouble("generation.waterworld.underwater-shelf.density", 0.90D);
        if (surfaceY >= seaLevel - 28) return plugin.getConfig().getDouble("generation.waterworld.ocean-floor.density", 0.75D);
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

    private boolean isValidHost(Material material, Environment environment) {
        if (isForbidden(material)) return false;
        if (environment == Environment.NETHER) return material == Material.NETHERRACK || material == Material.BASALT || material == Material.BLACKSTONE;
        return material == Material.STONE || material == Material.DEEPSLATE || material == Material.TUFF
                || material == Material.ANDESITE || material == Material.DIORITE || material == Material.GRANITE
                || material == Material.SAND || material == Material.RED_SAND || material == Material.GRAVEL;
    }

    private List<OreProfile> loadProfiles(ConfigurationSection section, Environment environment) {
        List<OreProfile> result = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection ore = section.getConfigurationSection(key);
            if (ore == null || !ore.getBoolean("enabled", true)) continue;
            try {
                Material material = Material.valueOf(key);
                if (environment == Environment.NETHER) {
                    if (!isNetherOre(material)) continue;
                } else if (isNetherOre(material)) continue;
                int weight = Math.max(1, ore.getInt("weight", 1));
                int minY = ore.getInt("min-y", environment == Environment.NETHER ? 0 : -64);
                int maxY = ore.getInt("max-y", environment == Environment.NETHER ? 128 : 128);
                int minSize = Math.max(1, ore.getInt("vein-size-min", 1));
                int maxSize = Math.max(minSize, ore.getInt("vein-size-max", minSize));
                double density = Math.max(0.0D, ore.getDouble("density", 1.0D));
                if (maxY >= minY && density > 0.0D) result.add(new OreProfile(material, weight, minY, maxY, minSize, maxSize, density));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Неизвестный материал руды в конфигурации: " + key);
            }
        }
        return result;
    }

    private boolean isNetherOre(Material material) {
        return material == Material.NETHER_QUARTZ_ORE || material == Material.NETHER_GOLD_ORE
                || material == Material.ANCIENT_DEBRIS || material == Material.NETHERITE_SCRAP;
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

    private int minY(List<OreProfile> profiles, int fallback) {
        return profiles.stream().mapToInt(OreProfile::minY).min().orElse(fallback);
    }

    private int maxY(List<OreProfile> profiles, int fallback) {
        return profiles.stream().mapToInt(OreProfile::maxY).max().orElse(fallback);
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

    private boolean contains(List<long[]> positions, int x, int y, int z) {
        for (long[] p : positions) if ((int) p[0] == x && (int) p[1] == y && (int) p[2] == z) return true;
        return false;
    }

    private int getInt(String modern, String legacy, int def) {
        return plugin.getConfig().contains(modern) ? plugin.getConfig().getInt(modern, def) : plugin.getConfig().getInt(legacy, def);
    }

    private static String chunkKey(UUID worldId, int x, int z) {
        return worldId + ":" + x + ":" + z;
    }

    private static long mixSeed(long seed, int cx, int cz) {
        long s = seed ^ ((long) cx * 0x9E3779B97F4A7C15L) ^ ((long) cz * 0xC2B2AE3D27D4EB4FL);
        s ^= s >>> 30;
        s *= 0xBF58476D1CE4E5B9L;
        s ^= s >>> 27;
        s *= 0x94D049BB133111EBL;
        return s ^ (s >>> 31);
    }

    private record ChunkRef(UUID worldId, int chunkX, int chunkZ) {}
    private record OreProfile(Material material, int weight, int minY, int maxY, int minSize, int maxSize, double density) {}
}
