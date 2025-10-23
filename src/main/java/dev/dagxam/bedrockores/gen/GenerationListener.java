package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.Chun kLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
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
        if (nodeManager.isChunkProcessed(world, cx, cz)) return;

        generateInChunk(chunk);
        nodeManager.markChunkProcessed(world, cx, cz);
        nodeManager.save();
    }

    private void generateInChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = minY + 3; // нижние 4 слоя над границей мира

        double chance = plugin.getConfig().getDouble("generation.chance-per-block", 0.008D);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = minY; y <= maxY; y++) {
                    if (random.nextDouble() > chance) continue;

                    int x = (chunk.getX() << 4) + lx;
                    int z = (chunk.getZ() << 4) + lz;
                    Location loc = new Location(world, x, y, z);

                    Material current = loc.getBlock().getType();
                    if (!isReplaceable(current)) continue;

                    if (!touchesBedrock(loc)) continue;       // строго вплотную к бедроку
                    if (adjacentToNode(loc)) continue;        // одиночные блоки — без "жил" рядом

                    Material ore = rollOre();
                    if (ore == null) continue;

                    int hits = nodeManager.randomHits();
                    nodeManager.addNode(loc, ore, hits);
                }
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

    private boolean adjacentToNode(Location loc) {
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return nmAt(x + 1, y, z, w) || nmAt(x - 1, y, z, w) ||
               nmAt(x, y + 1, z, w) || nmAt(x, y - 1, z, w) ||
               nmAt(x, y, z + 1, w) || nmAt(x, y, z - 1, w);
    }

    private boolean nmAt(int x, int y, int z, World w) {
        return nodeManager.isNode(new Location(w, x, y, z));
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
