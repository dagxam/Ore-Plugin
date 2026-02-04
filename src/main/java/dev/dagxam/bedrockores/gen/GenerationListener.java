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
 * Генерация узлов.
 *
 * Важное:
 * - Анти-цветы/анти-мусор: из ore-weights пропускаются только:
 *   - *_ORE
 *   - ANCIENT_DEBRIS
 *   - NETHERITE_SCRAP (виртуальная "руда": ставим ANCIENT_DEBRIS, дропаем scrap)
 * - Очередь генерации не "залипает": ключ снимается сразу после poll().
 */
public class GenerationListener implements Listener {
    private final Plugin plugin;
    private final NodeManager nodeManager;
    private final Random random = new Random();

    private Map<Material, Integer> weightsDefault = new LinkedHashMap<>();
    private Map<Material, Integer> weightsOverworld = null;
    private Map<Material, Integer> weightsNether = null;

    // === Очередь генерации ===
    private boolean queueEnabled = false;
    private int chunksPerTick = 1;

    private final ArrayDeque<long[]> chunkQueue = new ArrayDeque<>(); // [worldUidMSB, worldUidLSB, cx, cz]
    private final Set<String> queuedKeys = new HashSet<>();
    private BukkitTask queueTask = null;

    // Кэш "replaceable"
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
        this.queueEnabled = q != null && q.getBoolean("enabled", false);
        this.chunksPerTick = Math.max(1, q != null ? q.getInt("chunks-per-tick", 2) : 2);

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
            LinkedHashMap<Material, Integer> def = new LinkedHashMap<>();
            def.put(Material.DEEPSLATE_REDSTONE_ORE, 8);
            def.put(Material.DEEPSLATE_IRON_ORE, 6);
            def.put(Material.DEEPSLATE_GOLD_ORE, 3);
            def.put(Material.DEEPSLATE_COPPER_ORE, 4);
            def.put(Material.DEEPSLATE_COAL_ORE, 4);
            def.put(Material.DEEPSLATE_LAPIS_ORE, 3);
            def.put(Material.DEEPSLATE_DIAMOND_ORE, 1);
            def.put(Material.DEEPSLATE_EMERALD_ORE, 1);
            def.put(Material.ANCIENT_DEBRIS, 1);
            weightsDefault = def;
        }
    }

    // ===== Очередь =====

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
        String key = chunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (!queuedKeys.add(key)) return;
        UUID w = chunk.getWorld().getUID();
        chunkQueue.add(new long[]{w.getMostSignificantBits(), w.getLeastSignificantBits(), chunk.getX(), chunk.getZ()});
    }

    /**
     * Фикс "залипания очереди": ключ снимаем сразу после poll().
     */
    private void drainQueue() {
        if (chunkQueue.isEmpty()) return;

        int budget = chunksPerTick;
        while (budget-- > 0 && !chunkQueue.isEmpty()) {
            long[] e = chunkQueue.poll();
            UUID wid = new UUID(e[0], e[1]);
            int cx = (int) e[2], cz = (int) e[3];

            String qKey = chunkKey(wid, cx, cz);
            queuedKeys.remove(qKey);

            World world = Bukkit.getWorld(wid);
            if (world == null) continue;
            if (!plugin.getConfig().getStringList("enabled-worlds").contains(world.getName())) continue;

            if (!world.isChunkLoaded(cx, cz)) continue;

            Chunk chunk = world.getChunkAt(cx, cz);

            if (nodeManager.isChunkProcessed(world, cx, cz)) {
                nodeManager.processDueRespawnsInChunk(chunk);
                continue;
            }

            generateInChunk(chunk);
            nodeManager.markChunkProcessed(world, cx, cz);
            nodeManager.processDueRespawnsInChunk(chunk);
        }
    }

    private static String chunkKey(UUID world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    // ===== Анти-цветы (weights) =====

    private Map<Material, Integer> loadWeightsFiltered(String section) {
        LinkedHashMap<Material, Integer> map = new LinkedHashMap<>();
        ConfigurationSection w = plugin.getConfig().getConfigurationSection(section);
        if (w == null) return map;

        for (String k : w.getKeys(false)) {
            Material m;
            try { m = Material.valueOf(k); }
            catch (Exception ex) {
                plugin.getLogger().warning("[BedrockOres] Invalid material in " + section + ": " + k);
                continue;
            }

            int wt = w.getInt(k);
            if (wt <= 0) continue;

            if (!isAllowedOreMaterial(m)) {
                plugin.getLogger().warning(
                        "[BedrockOres] Blocked non-ore material in " + section + ": " + m +
                        " (allowed: *_ORE, ANCIENT_DEBRIS, NETHERITE_SCRAP)"
                );
                continue;
            }

            map.put(m, wt);
        }
        return map;
    }

    private boolean isAllowedOreMaterial(Material m) {
        if (m == Material.ANCIENT_DEBRIS) return true;
        if (m == Material.NETHERITE_SCRAP) return true; // виртуальная "руда"
        return m.name().endsWith("_ORE");
    }

    private Map<Material, Integer> weightsFor(World world) {
        if (world.getEnvironment() == Environment.NETHER && weightsNether != null) return weightsNether;
        if (world.getEnvironment() == Environment.NORMAL && weightsOverworld != null) return weightsOverworld;
        return weightsDefault;
    }

    // ===== Events =====

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        World world = e.getWorld();
        if (!plugin.getConfig().getStringList("enabled-worlds").contains(world.getName())) return;

        Chunk chunk = e.getChunk();
        int cx = chunk.getX(), cz = chunk.getZ();

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

    // ===== Generation =====

    public void generateInChunk(Chunk chunk) {
        World world = chunk.getWorld();
        Environment env = world.getEnvironment();

        Map<Material, Integer> weights = weightsFor(world);
        if (weights == null || weights.isEmpty()) return;

        // Netherite scrap разрешаем только в Nether (если случайно добавили в общий вес — всё равно не выпадет вне Nether)
        // End/прочие миры по умолчанию не генерим
        if (env != Environment.NORMAL && env != Environment.NETHER) return;

        // Y-диапазон: если не задан — берём узкую полосу у низа
        int minY, maxY;
        boolean hasMin = plugin.getConfig().isInt("generation.y-min");
        boolean hasMax = plugin.getConfig().isInt("generation.y-max");

        if (hasMin || hasMax) {
            minY = world.getMinHeight();
            maxY = world.getMaxHeight() - 1;
            if (hasMin) minY = Math.max(minY, plugin.getConfig().getInt("generation.y-min"));
            if (hasMax) maxY = Math.min(maxY, plugin.getConfig().getInt("generation.y-max"));
        } else {
            int band = Math.max(4, plugin.getConfig().getInt("generation.default-bedrock-band", 8));
            minY = world.getMinHeight();
            maxY = Math.min(world.getMaxHeight() - 1, minY + band);
        }

        int yLen = Math.max(1, (maxY - minY + 1));

        int minSpacing = Math.max(1, plugin.getConfig().getInt("generation.cluster.min-spacing", 4));
        int spacing2 = minSpacing * minSpacing;

        int clusterMin = Math.max(1, plugin.getConfig().getInt("generation.cluster.size-min", 1));
        int clusterMax = Math.max(clusterMin, plugin.getConfig().getInt("generation.cluster.size-max", 3));

        int targetClusters = Math.max(0, plugin.getConfig().getInt("generation.target-per-chunk", 12));
        int maxClusters = Math.max(targetClusters, plugin.getConfig().getInt("generation.max-per-chunk", 24));
        int fillAttemptsPerCluster = Math.max(5, plugin.getConfig().getInt("generation.fill-attempts-per-node", 25));

        double chance = plugin.getConfig().getDouble("generation.chance-per-block", 0.008D);
        chance *= plugin.getConfig().getDouble("generation.density-multiplier", 1.0D);
        if (chance < 0.0D) chance = 0.0D;

        int volume = 16 * 16 * yLen;
        int hardCap = Math.max(200, plugin.getConfig().getInt("generation.max-attempts-per-chunk", 1800));

        int expected = (int) Math.ceil(volume * chance);
        int attempts = Math.max(targetClusters * fillAttemptsPerCluster, expected * 2 + 100);
        attempts = Math.min(hardCap, Math.max(200, attempts));

        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        UUID wid = world.getUID();

        int placed = 0;
        List<int[]> placedCentersLocal = new ArrayList<>();

        while (attempts-- > 0 && placed < maxClusters) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            int y = minY + random.nextInt(yLen);

            Material host = fastType(chunk, x, y, z);
            if (host == null) continue;
            if (!isReplaceableCached(host, env)) continue;

            if (!farEnoughFromLocal2D(placedCentersLocal, x, z, spacing2)) continue;
            if (!farEnoughFromExistingNodes2D(wid, x, y, z, minSpacing, spacing2)) continue;

            Material ore = rollOre(weights, world);
            if (ore == null) continue;

            if (tryPlaceCluster(chunk, wid, x, y, z, ore, clusterMin, clusterMax, minY, maxY, minSpacing, spacing2, env)) {
                placedCentersLocal.add(new int[]{x, z});
                placed++;
            }
        }
    }

    private Material fastType(Chunk chunk, int x, int y, int z) {
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int lx = x - baseX;
        int lz = z - baseZ;
        if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16) return null;
        return chunk.getBlock(lx, y, lz).getType();
    }

    private boolean isReplaceableCached(Material m, Environment env) {
        if (env == Environment.NORMAL) {
            Boolean v = replaceableOverworldCache.get(m);
            if (v != null) return v;
            boolean ok = isReplaceableOverworld(m);
            replaceableOverworldCache.put(m, ok);
            return ok;
        }
        if (env == Environment.NETHER) {
            Boolean v = replaceableNetherCache.get(m);
            if (v != null) return v;
            boolean ok = (m == Material.NETHERRACK || m == Material.BASALT || m == Material.BLACKSTONE);
            replaceableNetherCache.put(m, ok);
            return ok;
        }
        return false;
    }

    private boolean isReplaceableOverworld(Material m) {
        if (m == Material.DEEPSLATE || m == Material.STONE || m == Material.TUFF) return true;

        boolean allowVariants = plugin.getConfig().getBoolean("generation.overworld.allow-stone-variants", true);
        if (!allowVariants) return false;

        String n = m.name();
        return n.endsWith("_STONE") || n.endsWith("ANDESITE") || n.endsWith("DIORITE") || n.endsWith("GRANITE");
    }

    private boolean farEnoughFromLocal2D(List<int[]> centers, int x, int z, int spacing2) {
        for (int[] c : centers) {
            int dx = c[0] - x;
            int dz = c[1] - z;
            if (dx * dx + dz * dz <= spacing2) return false;
        }
        return true;
    }

    private boolean farEnoughFromExistingNodes2D(UUID worldId, int x, int y, int z, int spacing, int spacing2) {
        for (int dy = -2; dy <= 2; dy++) {
            int yy = y + dy;
            for (int dx = -spacing; dx <= spacing; dx++) {
                for (int dz = -spacing; dz <= spacing; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > spacing2) continue;
                    if (nodeManager.isNode(worldId, x + dx, yy, z + dz)) return false;
                }
            }
        }
        return true;
    }

    private boolean tryPlaceCluster(Chunk chunk,
                                    UUID worldId,
                                    int cx, int cy, int cz,
                                    Material ore,
                                    int clusterMin, int clusterMax,
                                    int minY, int maxY,
                                    int minSpacing, int spacing2,
                                    Environment env) {

        if (fastType(chunk, cx, cy, cz) == null) return false;
        if (!isReplaceableCached(fastType(chunk, cx, cy, cz), env)) return false;

        int size = clusterMin + random.nextInt(clusterMax - clusterMin + 1);

        List<int[]> cluster = new ArrayList<>(size);
        cluster.add(new int[]{cx, cy, cz});

        int idx = 0;
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (cluster.size() < size && idx < cluster.size()) {
            int[] base = cluster.get(idx++);
            int bx = base[0], by = base[1], bz = base[2];

            for (int[] d : dirs) {
                if (cluster.size() >= size) break;
                int nx = bx + d[0], ny = by + d[1], nz = bz + d[2];
                if (ny < minY || ny > maxY) continue;

                if (fastType(chunk, nx, ny, nz) == null) continue;

                Material host = fastType(chunk, nx, ny, nz);
                if (!isReplaceableCached(host, env)) continue;
                if (!farEnoughFromExistingNodes2D(worldId, nx, ny, nz, minSpacing, spacing2)) continue;

                boolean dup = false;
                for (int[] p : cluster) {
                    if (p[0] == nx && p[1] == ny && p[2] == nz) { dup = true; break; }
                }
                if (!dup) cluster.add(new int[]{nx, ny, nz});
            }
        }

        if (cluster.isEmpty()) return false;

        World world = chunk.getWorld();
        for (int[] p : cluster) {
            Location l = new Location(world, p[0], p[1], p[2]);
            nodeManager.addNode(l, ore, nodeManager.randomHits());
        }
        return true;
    }

    private Material rollOre(Map<Material, Integer> weights, World world) {
        if (weights == null || weights.isEmpty()) return null;

        Environment env = world.getEnvironment();

        int total = 0;
        for (Map.Entry<Material, Integer> e : weights.entrySet()) {
            Material m = e.getKey();

            // NETHERITE_SCRAP разрешаем только в Nether
            if (m == Material.NETHERITE_SCRAP && env != Environment.NETHER) continue;

            // ANCIENT_DEBRIS тоже только Nether
            if (m == Material.ANCIENT_DEBRIS && env != Environment.NETHER) continue;

            total += e.getValue();
        }
        if (total <= 0) return null;

        int r = random.nextInt(total), acc = 0;
        for (Map.Entry<Material, Integer> e : weights.entrySet()) {
            Material m = e.getKey();

            if (m == Material.NETHERITE_SCRAP && env != Environment.NETHER) continue;
            if (m == Material.ANCIENT_DEBRIS && env != Environment.NETHER) continue;

            acc += e.getValue();
            if (r < acc) return m;
        }
        return null;
    }
}
