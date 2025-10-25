package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class GenerationListener implements Listener {
    private final Plugin plugin;
    private final NodeManager nodeManager;
    private final Random random = new Random();

    private final Map<Material, Integer> weights = new LinkedHashMap<>();

    // Очередь «ленивой» генерации
    private final Deque<GenTask> queue = new ArrayDeque<>();
    private BukkitTask queueTask;

    public GenerationListener(Plugin plugin, NodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
        reloadWeights();
        startQueue();
    }

    public void reloadWeights() {
        weights.clear();
        ConfigurationSection w = plugin.getConfig().getConfigurationSection("ore-weights");
        if (w != null) {
            for (String k : w.getKeys(false)) {
                try {
                    Material m = Material.valueOf(k);
                    int wt = w.getInt(k);
                    if (wt > 0) weights.put(m, wt);
                } catch (Exception ignored) {}
            }
        }
        if (weights.isEmpty()) {
            weights.put(Material.DEEPSLATE_REDSTONE_ORE, 8);
            weights.put(Material.DEEPSLATE_IRON_ORE, 6);
            weights.put(Material.DEEPSLATE_GOLD_ORE, 3);
            weights.put(Material.DEEPSLATE_COPPER_ORE, 4);
            weights.put(Material.DEEPSLATE_COAL_ORE, 4);
            weights.put(Material.DEEPSLATE_LAPIS_ORE, 3);
            weights.put(Material.DEEPSLATE_DIAMOND_ORE, 1);
            weights.put(Material.DEEPSLATE_EMERALD_ORE, 1);
        }
    }

    private void startQueue() {
        boolean enabled = plugin.getConfig().getBoolean("generation.queue.enabled", true);
        if (!enabled) return;
        if (queueTask != null) queueTask.cancel();

        queueTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (queue.isEmpty()) return;

            int chunksPerTick = Math.max(1, plugin.getConfig().getInt("generation.queue.chunks-per-tick", 2));
            int posPerTick    = Math.max(50, plugin.getConfig().getInt("generation.queue.positions-per-tick", 280));
            int fillPerTick   = Math.max(25, plugin.getConfig().getInt("generation.queue.fill-attempts-per-tick", 150));

            for (int processed = 0; processed < chunksPerTick; processed++) {
                GenTask t = queue.pollFirst(); // забираем голову очереди
                if (t == null) break;

                if (!t.isValid()) {
                    continue; // пропускаем битые
                }

                boolean done = t.step(posPerTick, fillPerTick);
                if (done) {
                    nodeManager.markChunkProcessed(t.getWorld(), t.getCx(), t.getCz());
                } else {
                    queue.addLast(t); // продолжим в следующий тик
                }
            }
        }, 1L, 1L);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        World world = e.getWorld();
        if (!plugin.getConfig().getStringList("enabled-worlds").contains(world.getName())) return;

        Chunk chunk = e.getChunk();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        if (!nodeManager.isChunkProcessed(world, cx, cz)) {
            queueChunk(chunk); // ставим задачу в очередь
        }

        // Восстановить «просроченные» узлы (лёгкая операция)
        nodeManager.processDueRespawnsInChunk(chunk);
    }

    // Публично: команда ставит чанк в очередь
    public void queueChunk(Chunk chunk) {
        boolean enabled = plugin.getConfig().getBoolean("generation.queue.enabled", true);
        if (!enabled) {
            // Фолбэк: синхронно (не рекомендуется на онлайне)
            generateInChunk(chunk);
            nodeManager.markChunkProcessed(chunk.getWorld(), chunk.getX(), chunk.getZ());
            return;
        }
        queue.add(new GenTask(plugin, nodeManager, random, weights, chunk));
    }

    // Синхронно (только для тестов/совместимости)
    public void generateInChunk(Chunk chunk) {
        new GenTask(plugin, nodeManager, random, weights, chunk).runSyncAll();
    }

    // ===== Задача генерации по одному чанку =====
    private static class GenTask {
        private final Plugin plugin;
        private final NodeManager nodeManager;
        private final Random random;
        private final Map<Material, Integer> weights;

        private final World world;
        private final int cx, cz;
        private final int minY, maxY;

        // Параметры кластеров и плотности
        private final int minSpacing, spacing2;
        private final int clusterMin, clusterMax;
        private final double chance;
        private final int targetClusters, maxClusters, fillAttemptsPerCluster;

        // Состояние итератора
        private int lx = 0, lz = 0, y;
        private boolean basePassDone = false;
        private int placedClusters = 0;
        private int fillAttemptsLeft = 0;

        // Локально размещённые центры кластеров (только X/Z)
        private final List<int[]> placedCentersLocal = new ArrayList<>();

        GenTask(Plugin plugin, NodeManager nodeManager, Random rnd, Map<Material, Integer> weights, Chunk chunk) {
            this.plugin = plugin;
            this.nodeManager = nodeManager;
            this.random = new Random(rnd.nextLong());
            this.weights = weights;

            this.world = chunk.getWorld();
            this.cx = chunk.getX();
            this.cz = chunk.getZ();

            // Диапазон Y
            int cfgMin = plugin.getConfig().getInt("generation.y-min", Integer.MIN_VALUE);
            int cfgMax = plugin.getConfig().getInt("generation.y-max", Integer.MIN_VALUE);
            int wMin = world.getMinHeight();
            int wMax = world.getMaxHeight() - 1;
            this.minY = (cfgMin == Integer.MIN_VALUE) ? wMin : Math.max(wMin, cfgMin);
            this.maxY = (cfgMax == Integer.MIN_VALUE) ? wMax : Math.min(wMax, cfgMax);

            // Кластеры и расстояния
            this.clusterMin = Math.max(1, plugin.getConfig().getInt("generation.cluster.size-min", 1));
            this.clusterMax = Math.max(this.clusterMin, plugin.getConfig().getInt("generation.cluster.size-max", 3));
            this.minSpacing = Math.max(1, plugin.getConfig().getInt("generation.cluster.min-spacing", 5));
            this.spacing2 = minSpacing * minSpacing;

            // Плотность
            double baseChance = plugin.getConfig().getDouble("generation.chance-per-block", 0.008D);
            double densityMul = Math.max(0.0D, plugin.getConfig().getDouble("generation.density-multiplier", 1.0D));
            this.chance = baseChance * densityMul;

            // Цели — считаем по КЛАСТЕРАМ
            this.targetClusters = Math.max(0, plugin.getConfig().getInt("generation.target-per-chunk", 12));
            this.maxClusters = Math.max(targetClusters, plugin.getConfig().getInt("generation.max-per-chunk", 24));
            this.fillAttemptsPerCluster = Math.max(5, plugin.getConfig().getInt("generation.fill-attempts-per-node", 25));

            this.y = minY;
        }

        public World getWorld() { return world; }
        public int getCx() { return cx; }
        public int getCz() { return cz; }
        public boolean isValid() { return world != null; }

        boolean step(int positionsBudget, int fillBudget) {
            if (!basePassDone) {
                positionsBudget = Math.max(1, positionsBudget);
                while (positionsBudget-- > 0) {
                    if (y > maxY) { basePassDone = true; break; }

                    int x = (cx << 4) + lx;
                    int z = (cz << 4) + lz;

                    if (random.nextDouble() <= chance) {
                        Location loc = new Location(world, x, y, z);
                        if (isReplaceable(loc.getBlock().getType())
                                && farEnoughFromLocalCenters2D(x, z, spacing2)
                                && farEnoughFromExistingClusters2D(loc, minSpacing, spacing2)) {
                            if (tryPlaceCluster(loc)) {
                                placedClusters++;
                                if (placedClusters >= maxClusters) {
                                    basePassDone = true;
                                    break;
                                }
                            }
                        }
                    }

                    // инкремент локальных координат
                    lx++;
                    if (lx >= 16) { lx = 0; lz++; }
                    if (lz >= 16) { lz = 0; y++; }
                }
                if (!basePassDone) return false; // базовая фаза ещё не завершена
                if (placedClusters < targetClusters) {
                    int need = targetClusters - placedClusters;
                    fillAttemptsLeft = Math.max(need * fillAttemptsPerCluster, need);
                }
            }

            // Фаза добора
            if (fillAttemptsLeft > 0 && placedClusters < targetClusters) {
                int attempts = Math.min(fillAttemptsLeft, Math.max(1, fillBudget));
                while (attempts-- > 0 && placedClusters < targetClusters) {
                    int rx = (cx << 4) + random.nextInt(16);
                    int rz = (cz << 4) + random.nextInt(16);
                    int ry = clamp(minY, maxY, minY + random.nextInt(Math.max(1, (maxY - minY + 1))));
                    Location loc = new Location(world, rx, ry, rz);

                    if (!isReplaceable(loc.getBlock().getType())) { fillAttemptsLeft--; continue; }
                    if (!farEnoughFromLocalCenters2D(rx, rz, spacing2)) { fillAttemptsLeft--; continue; }
                    if (!farEnoughFromExistingClusters2D(loc, minSpacing, spacing2)) { fillAttemptsLeft--; continue; }

                    if (tryPlaceCluster(loc)) {
                        placedClusters++;
                    }
                    fillAttemptsLeft--;
                    if (placedClusters >= maxClusters) break;
                }
                if (fillAttemptsLeft > 0 && placedClusters < targetClusters) return false;
            }

            return true; // задача завершена
        }

        void runSyncAll() {
            while (!step(4096, 4096)) { /* синхронно добиваем (только для тестов) */ }
        }

        private boolean isReplaceable(Material m) {
            if (m == Material.DEEPSLATE || m == Material.STONE || m == Material.TUFF) return true;
            String n = m.name();
            return n.endsWith("_STONE") || n.endsWith("ANDESITE") || n.endsWith("DIORITE") || n.endsWith("GRANITE") || n.endsWith("BASALT");
        }

        private boolean tryPlaceCluster(Location center) {
            // Проверяем ещё раз, что блок — замещаемый
            if (!isReplaceable(center.getBlock().getType())) return false;

            // Размер кластера 1..3 (из конфига)
            int size = clusterMin + random.nextInt(clusterMax - clusterMin + 1);

            // Собираем позиции кластера (соседние по 6 направлениям, без диагоналей)
            List<Location> cluster = new ArrayList<>(size);
            cluster.add(center);

            int idx = 0;
            while (cluster.size() < size && idx < cluster.size()) {
                Location base = cluster.get(idx++);
                for (int i = 0; i < 6 && cluster.size() < size; i++) {
                    int dx = 0, dy = 0, dz = 0;
                    switch (i) {
                        case 0: dx = 1; break;
                        case 1: dx = -1; break;
                        case 2: dy = 1; break;
                        case 3: dy = -1; break;
                        case 4: dz = 1; break;
                        case 5: dz = -1; break;
                    }
                    Location nloc = new Location(world, base.getBlockX() + dx, base.getBlockY() + dy, base.getBlockZ() + dz);
                    if (nloc.getBlockY() < minY || nloc.getBlockY() > maxY) continue;
                    if (!isReplaceable(nloc.getBlock().getType())) continue;

                    // Не ставим блоки кластера слишком близко к существующим кластерам по X/Z
                    if (!farEnoughFromExistingClusters2D(nloc, minSpacing, spacing2)) continue;

                    // Не добавляем дубликаты
                    boolean exists = false;
                    for (Location l : cluster) {
                        if (sameBlock(l, nloc)) { exists = true; break; }
                    }
                    if (!exists) cluster.add(nloc);
                }
                // Если не получилось добрать — выходим (размер будет меньше)
            }

            // Поставим фактически хотя бы 1 блок
            if (cluster.isEmpty()) return false;

            // Размещаем узлы
            for (Location l : cluster) {
                nodeManager.addNode(l, rollOre(), nodeManager.randomHits());
            }

            // Запоминаем центр, чтобы выдерживать дистанцию между кластерами
            placedCentersLocal.add(new int[]{center.getBlockX(), center.getBlockZ()});
            return true;
        }

        // Быстрый локальный чек по центрам, добавленным в этом чанке (только X/Z)
        private boolean farEnoughFromLocalCenters2D(int x, int z, int spacing2) {
            for (int[] c : placedCentersLocal) {
                int dx = c[0] - x;
                int dz = c[1] - z;
                if (dx*dx + dz*dz <= spacing2) return false;
            }
            return true;
        }

        // Проверка расстояния до УЖЕ СУЩЕСТВУЮЩИХ узлов плагина поблизости, только по X/Z.
        // Чтобы не было слишком дорого — проверяем слой по Y около точки (y±2).
        private boolean farEnoughFromExistingClusters2D(Location loc, int spacing, int spacing2) {
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            int y = loc.getBlockY();

            for (int dy = -2; dy <= 2; dy++) {
                int yy = y + dy;
                if (yy < minY || yy > maxY) continue;

                for (int dx = -spacing; dx <= spacing; dx++) {
                    for (int dz = -spacing; dz <= spacing; dz++) {
                        int d2 = dx*dx + dz*dz;
                        if (d2 > spacing2) continue;
                        Location check = new Location(world, x + dx, yy, z + dz);
                        if (nodeManager.isNode(check)) return false;
                    }
                }
            }
            return true;
        }

        private boolean sameBlock(Location a, Location b) {
            return a.getWorld() == b.getWorld()
                    && a.getBlockX() == b.getBlockX()
                    && a.getBlockY() == b.getBlockY()
                    && a.getBlockZ() == b.getBlockZ();
        }

        private int clamp(int min, int max, int v) {
            return Math.max(min, Math.min(max, v));
        }

        private Material rollOre() {
            int total = 0;
            for (int v : weights.values()) total += v;
            if (total <= 0) return Material.DEEPSLATE_COAL_ORE; // запасной вариант
            int r = random.nextInt(total), acc = 0;
            for (Map.Entry<Material, Integer> e : weights.entrySet()) {
                acc += e.getValue();
                if (r < acc) return e.getKey();
            }
            return Material.DEEPSLATE_COAL_ORE;
        }
    }
}
