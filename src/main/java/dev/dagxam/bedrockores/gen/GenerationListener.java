package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class GenerationListener implements Listener {
    private final Plugin plugin;
    private final NodeManager nodeManager;
    private final Random random = new Random();

    private final Map<Material, Integer> weights = new LinkedHashMap<>();

    public GenerationListener(Plugin plugin, NodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
        reloadWeights();
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

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        World world = e.getWorld();
        if (!plugin.getConfig().getStringList("enabled-worlds").contains(world.getName())) return;

        Chunk chunk = e.getChunk();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        if (!nodeManager.isChunkProcessed(world, cx, cz)) {
            generateInChunk(chunk);
            nodeManager.markChunkProcessed(world, cx, cz);
        }

        // Возрождаем просроченные узлы в этом чанке (если пришло время)
        nodeManager.processDueRespawnsInChunk(chunk);
    }

    // Публично — для команды
    public void generateInChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int layers = Math.max(1, plugin.getConfig().getInt("generation.layers-from-bottom", 7));
        int maxY = minY + (layers - 1);

        int minSpacing = Math.max(1, plugin.getConfig().getInt("generation.min-spacing", 4));
        int spacing2 = minSpacing * minSpacing;

        double baseChance = plugin.getConfig().getDouble("generation.chance-per-block", 0.008D);
        double densityMul = Math.max(0.0D, plugin.getConfig().getDouble("generation.density-multiplier", 1.0D));
        double chance = baseChance * densityMul;

        int targetPerChunk = Math.max(0, plugin.getConfig().getInt("generation.target-per-chunk", 12));
        int maxPerChunk = Math.max(targetPerChunk, plugin.getConfig().getInt("generation.max-per-chunk", 24));
        int fillAttemptsPerNode = Math.max(5, plugin.getConfig().getInt("generation.fill-attempts-per-node", 25));

        int placed = 0;
        List<int[]> placedLocal = new ArrayList<>();

        // 1) Базовый проход по блокам (вероятностный)
        outer:
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = minY; y <= maxY; y++) {
                    if (random.nextDouble() > chance) continue;

                    int x = (chunk.getX() << 4) + lx;
                    int z = (chunk.getZ() << 4) + lz;
                    Location loc = new Location(world, x, y, z);

                    Material current = loc.getBlock().getType();
                    if (!isReplaceable(current)) continue;

                    if (!touchesBedrock(loc)) continue;

                    if (!farEnoughFromExistingNodes(loc, minSpacing, spacing2)) continue;
                    if (!farEnoughFromLocal(placedLocal, x, y, z, spacing2)) continue;

                    Material ore = rollOre();
                    if (ore == null) continue;

                    int hits = nodeManager.randomHits();
                    nodeManager.addNode(loc, ore, hits);
                    placedLocal.add(new int[]{x, y, z});
                    placed++;

                    if (placed >= maxPerChunk) break outer;
                }
            }
        }

        // 2) Добор до целевого числа узлов (случайные попытки)
        if (placed < targetPerChunk) {
            int need = targetPerChunk - placed;
            int attempts = Math.max(need * fillAttemptsPerNode, need); // страховка

            while (placed < targetPerChunk && attempts-- > 0) {
                int rx = (chunk.getX() << 4) + random.nextInt(16);
                int rz = (chunk.getZ() << 4) + random.nextInt(16);
                int ry = minY + random.nextInt(Math.max(1, (maxY - minY + 1)));

                Location loc = new Location(world, rx, ry, rz);
                Material current = loc.getBlock().getType();
                if (!isReplaceable(current)) continue;
                if (!touchesBedrock(loc)) continue;
                if (!farEnoughFromExistingNodes(loc, minSpacing, spacing2)) continue;
                if (!farEnoughFromLocal(placedLocal, rx, ry, rz, spacing2)) continue;

                Material ore = rollOre();
                if (ore == null) continue;

                int hits = nodeManager.randomHits();
                nodeManager.addNode(loc, ore, hits);
                placedLocal.add(new int[]{rx, ry, rz});
                placed++;

                if (placed >= maxPerChunk) break;
            }
        }
    }

    private boolean isReplaceable(Material m) {
        if (m == Material.DEEPSLATE || m == Material.STONE || m == Material.TUFF) return true;
        if (m.name().endsWith("_STONE") || m.name().endsWith("ANDESITE") || m.name().endsWith("DIORITE") || m.name().endsWith("GRANITE")) {
            return true;
        }
        return false;
    }

    private boolean touchesBedrock(Location loc) {
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return w.getBlockAt(x + 1, y, z).getType() == Material.BEDROCK
            || w.getBlockAt(x - 1, y, z).getType() == Material.BEDROCK
            || w.getBlockAt(x, y + 1, z).getType() == Material.BEDROCK
            || w.getBlockAt(x, y - 1, z).getType() == Material.BEDROCK
            || w.getBlockAt(x, y, z + 1).getType() == Material.BEDROCK
            || w.getBlockAt(x, y, z - 1).getType() == Material.BEDROCK;
    }

    private boolean farEnoughFromExistingNodes(Location loc, int spacing, int spacing2) {
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        for (int dx = -spacing; dx <= spacing; dx++) {
            for (int dy = -spacing; dy <= spacing; dy++) {
                for (int dz = -spacing; dz <= spacing; dz++) {
                    int d2 = dx*dx + dy*dy + dz*dz;
                    if (d2 > spacing2) continue;
                    if (nodeManager.isNode(new Location(w, x + dx, y + dy, z + dz))) return false;
                }
            }
        }
        return true;
    }

    private boolean farEnoughFromLocal(List<int[]> local, int x, int y, int z, int spacing2) {
        for (int[] p : local) {
            int dx = p[0] - x;
            int dy = p[1] - y;
            int dz = p[2] - z;
            if (dx*dx + dy*dy + dz*dz <= spacing2) return false;
        }
        return true;
    }

    private Material rollOre() {
        int total = weights.values().stream().mapToInt(i -> i).sum();
        if (total <= 0) return null;
        int r = random.nextInt(total);
        int acc = 0;
        for (Map.Entry<Material, Integer> e : weights.entrySet()) {
            acc += e.getValue();
            if (r < acc) return e.getKey();
        }
        return null;
    }
}
