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
import org.bukkit.util.BoundingBox;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class GenerationListener implements Listener {
    private final Plugin plugin;
    private final NodeManager nodeManager;
    private final Random random = new Random();

    private final Map<Material, Integer> weights = new LinkedHashMap<>();

    public GenerationListener(Plugin plugin, NodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;

        // загрузим веса
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
            // дефолт
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
        // сохраним флаг
        nodeManager.save();
    }

    private void generateInChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = minY + 3; // первые 4 уровня над бедроком

        double chance = plugin.getConfig().getDouble("generation.chance-per-block", 0.008D);
        int radius = plugin.getConfig().getInt("generation.bedrock-radius", 1);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = minY; y <= maxY; y++) {
                    if (random.nextDouble() > chance) continue;

                    int x = (chunk.getX() << 4) + lx;
                    int z = (chunk.getZ() << 4) + lz;
                    Location loc = new Location(world, x, y, z);

                    // Блок должен быть камень/глубин.сланец/туф, не воздух/вода
                    Material current = loc.getBlock().getType();
                    if (!isReplaceable(current)) continue;

                    if (!isNearBedrock(loc, radius)) continue;

                    Material ore = rollOre();
                    if (ore == null) continue;

                    // Создаем узел
                    int hits = nodeManager.randomHits();
                    nodeManager.addNode(loc, ore, hits);
                }
            }
        }
    }

    private boolean isReplaceable(Material m) {
        if (m == Material.DEEPSLATE || m == Material.STONE || m == Material.TUFF) return true;
        // иногда внизу попадаются базальт/андезит и т.п.
        if (m.name().endsWith("_STONE") || m.name().endsWith("ANDESITE") || m.name().endsWith("DIORITE") || m.name().endsWith("GRANITE")) {
            return true;
        }
        return false;
    }

    private boolean isNearBedrock(Location loc, int radius) {
        World w = loc.getWorld();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Material m = w.getBlockAt(loc.getBlockX() + dx, loc.getBlockY() + dy, loc.getBlockZ() + dz).getType();
                    if (m == Material.BEDROCK) {
                        return true;
                    }
                }
            }
        }
        return false;
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
