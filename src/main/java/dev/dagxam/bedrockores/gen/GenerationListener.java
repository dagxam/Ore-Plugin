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
            int posPerTick = Math.max(50, plugin.getConfig().getInt("generation.queue.positions-per-tick", 280));
            int fillPerTick = Math.max(25, plugin.getConfig().getInt("generation.queue.fill-attempts-per-tick", 150));

            int processed = 0;
            Iterator<GenTask> it = queue.iterator();
            while (it.hasNext() && processed < chunksPerTick) {
                GenTask t = it.next();
                if (!t.isValid()) { it.remove(); continue; }
                boolean done = t.step(posPerTick, fillPerTick);
                if (done) {
                    nodeManager.markChunkProcessed(t.getWorld(), t.getCx(), t.getCz());
                    it.remove();
                }
                processed++;
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
        if (nodeManager.isChunkProcessed(world, cx, cz)) {
            nodeManager.processDueRespawnsInChunk(chunk);
            return;
        }

        // Ставим задачу генерации в очередь
        queueChunk(chunk);

        // Восстановим просроченные респавны (лёгкая операция)
        nodeManager.processDueRespawnsInChunk(chunk);
    }

    // Публично: используется командами
    public void queueChunk(Chunk chunk) {
        boolean enabled = plugin.getConfig().getBoolean("generation.queue.enabled", true);
        if (!enabled) {
            // фолбэк на синхронный режим (не рекомендуется на живом сервере)
            generateInChunk(chunk);
            nodeManager.markChunkProcessed(chunk.getWorld(), chunk.getX(), chunk.getZ());
            return;
        }
        queue.add(new GenTask(plugin, nodeManager, random, weights, chunk));
    }

    // Синхронная генерация — только для тестов/совместимости
    public void generateInChunk(Chunk chunk) {
        new GenTask(plugin, nodeManager, random, weights, chunk).runSyncAll();
    }

    // ===== ВНУТРЕННЯЯ ЗАДАЧА ПО ОДНОМУ ЧАНКУ =====
    private static class GenTask {
        private final Plugin plugin;
        private final NodeManager nodeManager;
        private final Random random;
        private final Map<Material, Integer> weights;

        private final World world;
        private final int cx, cz;
        private final int minY, maxY;

        private final int minSpacing, spacing2;
        private final double chance;
        private final int targetPerChunk, maxPerChunk, fillAttemptsPerNode;

        private int lx = 0, lz = 0, y;
        private boolean basePassDone = false;
        private int placed = 0;
        private int fillAttemptsLeft = 0;

        private final List<int[]> placedLocal = new ArrayList<>();

        GenTask(Plugin plugin, NodeManager nodeManager, Random rnd, Map<Material, Integer> weights, Chunk chunk) {
            this.plugin = plugin;
            this.nodeManager = nodeManager;
            this.random = new Random(rnd.nextLong());
            this.weights = weights;

            this.world = chunk.getWorld();
            this.cx = chunk.getX();
            this.cz = chunk.getZ();

            int layers = Math.max(1, plugin.getConfig().getInt("generation.layers-from-bottom", 7));
            this.minY = world.getMinHeight();
            this.maxY = minY + (layers - 1);

            this.minSpacing = Math.max(1, plugin.getConfig().getInt("generation.min-spacing", 4));
            this.spacing2 = minSpacing * minSpacing;

            double baseChance = plugin.getConfig().getDouble("generation.chance-per-block", 0.008D);
            double densityMul = Math.max(0.0D, plugin.getConfig().getDouble("generation.density-multiplier", 1.0D));
            this.chance = baseChance * densityMul;

            this.targetPerChunk = Math.max(0, plugin.getConfig().getInt("generation.target-per-chunk", 12));
            this.maxPerChunk = Math.max(targetPerChunk, plugin.getConfig().getInt("generation.max-per-chunk", 24));
            this.fillAttemptsPerNode = Math.max(5, plugin.getConfig().getInt("generation.fill-attempts-per-node", 25));

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
                        Material current = loc.getBlock().getType();
                        if (isReplaceable(current) && touchesBedrock(loc)
                                && farEnoughFromExistingNodes2D(loc, minSpacing, spacing2)
                                && farEnoughFromLocal2D(placedLocal, x, z, spacing2)) {
                            Material ore = rollOre();
                            if (ore != null) {
                                nodeManager.addNode(loc, ore, nodeManager.randomHits());
                                placedLocal.add(new int[]{x, y, z});
                                placed++;
                                if (placed >= maxPerChunk) {
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
                if (!basePassDone) return false; // база ещё не закончена
                // рассчитать «добор»
                if (placed < targetPerChunk) {
                    int need = targetPerChunk - placed;
                    fillAttemptsLeft = Math.max(need * fillAttemptsPerNode, need);
                }
            }

            // Фаза «добора»
            if (fillAttemptsLeft > 0 && placed < targetPerChunk) {
                int attempts = Math.min(fillAttemptsLeft, Math.max(1, fillBudget));
                while (attempts-- > 0 && placed < targetPerChunk) {
                    int rx = (cx << 4) + random.nextInt(16);
                    int rz = (cz << 4) + random.nextInt(16);
                    int ry = minY + random.nextInt(Math.max(1, (maxY - minY + 1)));

                    Location loc = new Location(world, rx, ry, rz);
                    Material current = loc.getBlock().getType();
                    if (!isReplaceable(current)) { fillAttemptsLeft--; continue; }
                    if (!touchesBedrock(loc)) { fillAttemptsLeft--; continue; }
                    if (!farEnoughFromExistingNodes2D(loc, minSpacing, spacing2)) { fillAttemptsLeft--; continue; }
                    if (!farEnoughFromLocal2D(placedLocal, rx, rz, spacing2)) { fillAttemptsLeft--; continue; }

                    Material ore = rollOre();
                    if (ore == null) { fillAttemptsLeft--; continue; }

                    nodeManager.addNode(loc, ore, nodeManager.randomHits());
                    placedLocal.add(new int[]{rx, ry, rz});
                    placed++;
                    fillAttemptsLeft--;
                    if (placed >= maxPerChunk) break;
                }
                if (fillAttemptsLeft > 0 && placed < targetPerChunk) return false;
            }

            return true; // задача завершена
        }

        void runSyncAll() {
            while (!step(4096, 4096)) { /* синхронно добиваем (только для тестов!) */ }
        }

        private boolean isReplaceable(Material m) {
            if (m == Material.DEEPSLATE || m == Material.STONE || m == Material.TUFF) return true;
            String n = m.name();
            return n.endsWith("_STONE") || n.endsWith("ANDESITE") || n.endsWith("DIORITE") || n.endsWith("GRANITE");
        }

        private boolean touchesBedrock(Location loc) {
            World w = loc.getWorld();
            int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
            return w.getBlockAt(x + 1, y, z).getType() == Material.BEDROCK
                || w.getBlockAt(x - 1, y, z).getType() == Material.BEDROCK
                || w.getBlockAt(x, y + 1, z).getType() == Material.BEDROCK
                || w.getBlockAt(x, y - 1, z).getType() == Material.BEDROCK
                || w.getBlockAt(x, y, z + 1).getType() == Material.BEDROCK
                || w.getBlockAt(x, y, z - 1).getType() == Material.BEDROCK;
        }

        // Дешевая проверка: только X/Z (горизонтальная дистанция), без прохода по Y
        private boolean farEnoughFromExistingNodes2D(Location loc, int spacing, int spacing2) {
            World w = loc.getWorld();
            int x = loc.getBlockX(), z = loc.getBlockZ(), y = loc.getBlockY();
            for (int dx = -spacing; dx <= spacing; dx++) {
                for (int dz = -spacing; dz <= spacing; dz++) {
                    int d2 = dx*dx + dz*dz;
                    if (d2 > spacing2) continue;
                    if (nodeManager.isNode(new Location(w, x + dx, y, z + dz))) return false;
                }
            }
            return true;
        }

        private boolean farEnoughFromLocal2D(List<int[]> local, int x, int z, int spacing2) {
            for (int[] p : local) {
                int dx = p[0] - x;
                int dz = p[2] - z;
                if (dx*dx + dz*dz <= spacing2) return false;
            }
            return true;
        }

        private Material rollOre() {
            int total = 0;
            for (int v : weights.values()) total += v;
            if (total <= 0) return null;
            int r = random.nextInt(total), acc = 0;
            for (Map.Entry<Material, Integer> e : weights.entrySet()) {
                acc += e.getValue();
                if (r < acc) return e.getKey();
            }
            return null;
        }
    }
}
