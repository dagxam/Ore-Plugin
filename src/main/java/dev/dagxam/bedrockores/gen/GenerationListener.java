package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
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

import java.util.*;

public class GenerationListener implements Listener {
    private final Plugin plugin;
    private final NodeManager nodeManager;
    private final Random random = new Random();

    private Map<Material, Integer> weightsDefault = new LinkedHashMap<>();
    private Map<Material, Integer> weightsOverworld = null;
    private Map<Material, Integer> weightsNether = null;

    public GenerationListener(Plugin plugin, NodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
        reloadWeights();
    }

    public void reloadWeights() {
        weightsDefault = loadWeights("ore-weights");
        Map<Material, Integer> ow = loadWeights("ore-weights-overworld");
        Map<Material, Integer> ne = loadWeights("ore-weights-nether");
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

    private Map<Material, Integer> loadWeights(String section) {
        LinkedHashMap<Material, Integer> map = new LinkedHashMap<>();
        ConfigurationSection w = plugin.getConfig().getConfigurationSection(section);
        if (w != null) {
            for (String k : w.getKeys(false)) {
                try {
                    Material m = Material.valueOf(k);
                    int wt = w.getInt(k);
                    if (wt > 0) map.put(m, wt);
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private Map<Material, Integer> weightsFor(World world) {
        if (world.getEnvironment() == Environment.NETHER && weightsNether != null) return weightsNether;
        if (world.getEnvironment() == Environment.NORMAL && weightsOverworld != null) return weightsOverworld;
        return weightsDefault;
    }

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

        generateInChunk(chunk);
        nodeManager.markChunkProcessed(world, cx, cz);
        nodeManager.processDueRespawnsInChunk(chunk);
    }

    // ————— Генерация строго в пределах данного чанка —————
    public void generateInChunk(Chunk chunk) {
        World world = chunk.getWorld();

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        if (plugin.getConfig().isInt("generation.y-min")) {
            minY = Math.max(minY, plugin.getConfig().getInt("generation.y-min"));
        }
        if (plugin.getConfig().isInt("generation.y-max")) {
            maxY = Math.min(maxY, plugin.getConfig().getInt("generation.y-max"));
        }

        double chance = plugin.getConfig().getDouble("generation.chance-per-block", 0.008D);
        chance *= plugin.getConfig().getDouble("generation.density-multiplier", 1.0D);
        if (chance < 0.0D) chance = 0.0D;

        int minSpacing = Math.max(1, plugin.getConfig().getInt("generation.cluster.min-spacing", 4));
        int spacing2 = minSpacing * minSpacing;

        int clusterMin = Math.max(1, plugin.getConfig().getInt("generation.cluster.size-min", 1));
        int clusterMax = Math.max(clusterMin, plugin.getConfig().getInt("generation.cluster.size-max", 3));

        int targetClusters = Math.max(0, plugin.getConfig().getInt("generation.target-per-chunk", 12));
        int maxClusters = Math.max(targetClusters, plugin.getConfig().getInt("generation.max-per-chunk", 24));
        int fillAttemptsPerCluster = Math.max(5, plugin.getConfig().getInt("generation.fill-attempts-per-node", 25));

        Map<Material, Integer> weights = weightsFor(world);
        if (weights == null || weights.isEmpty()) return;

        int placed = 0;
        List<int[]> placedCentersLocal = new ArrayList<>();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        // Базовый проход
        outer:
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = minY; y <= maxY; y++) {
                    if (random.nextDouble() > chance) continue;

                    int x = baseX + lx;
                    int z = baseZ + lz;

                    Material host = fastType(chunk, x, y, z);
                    if (host == null) continue;               // вышли за границы чанка — пропускаем
                    if (!isReplaceable(host, world)) continue;

                    if (!farEnoughFromLocal2D(placedCentersLocal, x, z, spacing2)) continue;
                    if (!farEnoughFromExistingNodes2D(world, x, y, z, minSpacing, spacing2)) continue;

                    Material ore = rollOre(weights, world);
                    if (ore == null) continue;

                    if (tryPlaceCluster(chunk, x, y, z, ore, clusterMin, clusterMax, minY, maxY, minSpacing, spacing2)) {
                        placedCentersLocal.add(new int[]{x, z});
                        placed++;
                        if (placed >= maxClusters) break outer;
                    }
                }
            }
        }

        // Добор до целевого количества кластеров
        if (placed < targetClusters) {
            int need = targetClusters - placed;
            int attempts = Math.max(need * fillAttemptsPerCluster, need);

            while (attempts-- > 0 && placed < targetClusters) {
                int rx = baseX + random.nextInt(16);
                int rz = baseZ + random.nextInt(16);
                int ry = minY + random.nextInt(Math.max(1, (maxY - minY + 1)));

                Material host = fastType(chunk, rx, ry, rz);
                if (host == null || !isReplaceable(host, world)) continue;

                if (!farEnoughFromLocal2D(placedCentersLocal, rx, rz, spacing2)) continue;
                if (!farEnoughFromExistingNodes2D(world, rx, ry, rz, minSpacing, spacing2)) continue;

                Material ore = rollOre(weights, world);
                if (ore == null) continue;

                if (tryPlaceCluster(chunk, rx, ry, rz, ore, clusterMin, clusterMax, minY, maxY, minSpacing, spacing2)) {
                    placedCentersLocal.add(new int[]{rx, rz});
                    placed++;
                }
            }
        }
    }

    // Быстрый тип блока в текущем чанке. Если (x,z) не в чанке — вернём null и пропустим.
    private Material fastType(Chunk chunk, int x, int y, int z) {
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int lx = x - baseX;
        int lz = z - baseZ;
        if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16) return null;
        return chunk.getBlock(lx, y, lz).getType();
    }

    private boolean isReplaceable(Material m, World w) {
        // Оверворлд
        if (w.getEnvironment() == Environment.NORMAL) {
            if (m == Material.DEEPSLATE || m == Material.STONE || m == Material.TUFF) return true;
            String n = m.name();
            if (n.endsWith("_STONE") || n.endsWith("ANDESITE") || n.endsWith("DIORITE") || n.endsWith("GRANITE")) return true;
            return false;
        }
        // Ад
        if (w.getEnvironment() == Environment.NETHER) {
            return (m == Material.NETHERRACK || m == Material.BASALT || m == Material.BLACKSTONE);
        }
        // Прочее (Энд) — по умолчанию отключено (можно добавить при необходимости)
        return false;
    }

    private boolean farEnoughFromLocal2D(List<int[]> centers, int x, int z, int spacing2) {
        for (int[] c : centers) {
            int dx = c[0] - x;
            int dz = c[1] - z;
            if (dx * dx + dz * dz <= spacing2) return false;
        }
        return true;
    }

    // Проверяем существующие узлы (наши) поблизости (малый радиус по Y)
    private boolean farEnoughFromExistingNodes2D(World w, int x, int y, int z, int spacing, int spacing2) {
        for (int dy = -2; dy <= 2; dy++) {
            int yy = y + dy;
            for (int dx = -spacing; dx <= spacing; dx++) {
                for (int dz = -spacing; dz <= spacing; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > spacing2) continue;
                    if (nodeManager.isNode(new Location(w, x + dx, yy, z + dz))) return false;
                }
            }
        }
        return true;
    }

    // Кластер растём только внутри текущего чанка — никаких выходов в соседние
    private boolean tryPlaceCluster(Chunk chunk,
                                    int cx, int cy, int cz,
                                    Material ore,
                                    int clusterMin, int clusterMax,
                                    int minY, int maxY,
                                    int minSpacing, int spacing2) {

        World world = chunk.getWorld();
        if (fastType(chunk, cx, cy, cz) == null) return false;           // за пределами чанка
        if (!isReplaceable(fastType(chunk, cx, cy, cz), world)) return false;

        int size = clusterMin + random.nextInt(clusterMax - clusterMin + 1);
        List<Location> cluster = new ArrayList<>(size);
        cluster.add(new Location(world, cx, cy, cz));

        int idx = 0;
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (cluster.size() < size && idx < cluster.size()) {
            Location base = cluster.get(idx++);
            int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();

            for (int[] d : dirs) {
                if (cluster.size() >= size) break;
                int nx = bx + d[0], ny = by + d[1], nz = bz + d[2];
                if (ny < minY || ny > maxY) continue;

                // Только текущий чанк!
                if (fastType(chunk, nx, ny, nz) == null) continue;

                Material host = fastType(chunk, nx, ny, nz);
                if (!isReplaceable(host, world)) continue;
                if (!farEnoughFromExistingNodes2D(world, nx, ny, nz, minSpacing, spacing2)) continue;

                boolean dup = false;
                for (Location l : cluster) {
                    if (l.getBlockX()==nx && l.getBlockY()==ny && l.getBlockZ()==nz) { dup = true; break; }
                }
                if (!dup) cluster.add(new Location(world, nx, ny, nz));
            }
        }

        if (cluster.isEmpty()) return false;

        for (Location l : cluster) {
            nodeManager.addNode(l, ore, nodeManager.randomHits());
        }
        return true;
    }

    private Material rollOre(Map<Material, Integer> weights, World world) {
        if (weights == null || weights.isEmpty()) return null;
        // Небольшая защита: ANCIENT_DEBRIS только в аду
        int total = 0;
        for (Map.Entry<Material, Integer> e : weights.entrySet()) {
            if (e.getKey() == Material.ANCIENT_DEBRIS && world.getEnvironment() != Environment.NETHER) continue;
            total += e.getValue();
        }
        if (total <= 0) return null;

        int r = random.nextInt(total), acc = 0;
        for (Map.Entry<Material, Integer> e : weights.entrySet()) {
            if (e.getKey() == Material.ANCIENT_DEBRIS && world.getEnvironment() != Environment.NETHER) continue;
            acc += e.getValue();
            if (r < acc) return e.getKey();
        }
        return null;
    }
}
