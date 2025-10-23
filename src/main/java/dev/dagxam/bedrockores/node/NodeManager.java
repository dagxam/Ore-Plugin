package dev.dagxam.bedrockores.node;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class NodeManager {
    private final Plugin plugin;

    // Ключ: worldUUID:x:y:z
    private final Map<String, NodeData> nodes = new HashMap<>();
    // Обработанные чанки (чтобы не генерировать повторно)
    private final Map<UUID, Set<Long>> processedChunks = new HashMap<>();

    private final File dataFile;

    public NodeManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "nodes.yml");
    }

    public static String key(Location loc) {
        return loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    public boolean isNode(Location loc) {
        return nodes.containsKey(key(loc));
    }

    public NodeData getNode(Location loc) {
        return nodes.get(key(loc));
    }

    public void addNode(Location loc, Material oreMaterial, int hits) {
        int max = hits;
        NodeData nd = new NodeData(oreMaterial, hits, max);
        nodes.put(key(loc), nd);
        loc.getBlock().setType(oreMaterial, false);
    }

    public void removeNode(Location loc) {
        nodes.remove(key(loc));
    }

    public void markChunkProcessed(World world, int cx, int cz) {
        processedChunks.computeIfAbsent(world.getUID(), k -> new HashSet<>()).add(chunkKey(cx, cz));
    }

    public boolean isChunkProcessed(World world, int cx, int cz) {
        return processedChunks.getOrDefault(world.getUID(), Collections.emptySet()).contains(chunkKey(cx, cz));
    }

    public void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection nodesSec = yml.getConfigurationSection("nodes");
        if (nodesSec != null) {
            for (String id : nodesSec.getKeys(false)) {
                ConfigurationSection s = nodesSec.getConfigurationSection(id);
                try {
                    UUID worldId = UUID.fromString(Objects.requireNonNull(s.getString("world")));
                    int x = s.getInt("x");
                    int y = s.getInt("y");
                    int z = s.getInt("z");
                    Material mat = Material.valueOf(s.getString("type"));
                    int hits = s.getInt("hits");
                    int maxHits = s.getInt("maxHits");

                    World w = Bukkit.getWorld(worldId);
                    if (w == null) continue;
                    Location loc = new Location(w, x, y, z);

                    nodes.put(key(loc), new NodeData(mat, hits, maxHits));
                    // восстановим отображение блока, если кто-то его менял
                    if (loc.getBlock().getType() != mat) {
                        loc.getBlock().setType(mat, false);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Bad node entry: " + id + " -> " + e.getMessage());
                }
            }
        }

        ConfigurationSection pcSec = yml.getConfigurationSection("processedChunks");
        if (pcSec != null) {
            for (String worldKey : pcSec.getKeys(false)) {
                try {
                    UUID worldId = UUID.fromString(worldKey);
                    List<String> list = pcSec.getStringList(worldKey);
                    Set<Long> set = new HashSet<>();
                    for (String s : list) {
                        String[] p = s.split(":");
                        int cx = Integer.parseInt(p[0]);
                        int cz = Integer.parseInt(p[1]);
                        set.add(chunkKey(cx, cz));
                    }
                    processedChunks.put(worldId, set);
                } catch (Exception e) {
                    plugin.getLogger().warning("Bad processedChunks entry for " + worldKey);
                }
            }
        }

        plugin.getLogger().info("Loaded " + nodes.size() + " nodes; processedChunks worlds: " + processedChunks.size());
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
            String id = "n" + (i++);
            String[] parts = e.getKey().split(":");
            UUID world = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);

            NodeData nd = e.getValue();
            String path = "nodes." + id;
            yml.set(path + ".world", world.toString());
            yml.set(path + ".x", x);
            yml.set(path + ".y", y);
            yml.set(path + ".z", z);
            yml.set(path + ".type", nd.oreMaterial.name());
            yml.set(path + ".hits", nd.hitsRemaining);
            yml.set(path + ".maxHits", nd.maxHits);
        }

        for (Map.Entry<UUID, Set<Long>> e : processedChunks.entrySet()) {
            List<String> list = new ArrayList<>();
            for (Long ck : e.getValue()) {
                int cx = (int) (ck >> 32);
                int cz = (int) (ck & 0xffffffffL);
                list.add(cx + ":" + cz);
            }
            yml.set("processedChunks." + e.getKey().toString(), list);
        }

        try {
            yml.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save nodes.yml: " + ex.getMessage());
        }
    }

    public int randomHits() {
        int min = plugin.getConfig().getInt("generation.min-hits", 15);
        int max = plugin.getConfig().getInt("generation.max-hits", 25);
        if (min < 1) min = 1;
        if (max < min) max = min;
        return min + new Random().nextInt(max - min + 1);
    }
}
