package dev.dagxam.bedrockores.node;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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

    private final Map<String, NodeData> nodes = new HashMap<>();
    private final Map<UUID, Set<Long>> processedChunks = new HashMap<>();
    private final Map<String, RespawnData> respawns = new HashMap<>();

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

    public boolean isNode(Location loc) { return nodes.containsKey(key(loc)); }
    public NodeData getNode(Location loc) { return nodes.get(key(loc)); }

    public void addNode(Location loc, Material oreMaterial, int hits) {
        int max = hits;
        NodeData nd = new NodeData(oreMaterial, hits, max);
        nodes.put(key(loc), nd);
        loc.getBlock().setType(oreMaterial, false);
    }

    public void removeNode(Location loc) { nodes.remove(key(loc)); }

    public void markChunkProcessed(World world, int cx, int cz) {
        processedChunks.computeIfAbsent(world.getUID(), k -> new HashSet<>()).add(chunkKey(cx, cz));
    }

    public boolean isChunkProcessed(World world, int cx, int cz) {
        return processedChunks.getOrDefault(world.getUID(), Collections.emptySet()).contains(chunkKey(cx, cz));
    }

    public void clearProcessedFlags(World world) { processedChunks.remove(world.getUID()); }
    public void clearAllProcessedFlags() { processedChunks.clear(); }

    public int removeNodesInChunk(Chunk chunk) {
        UUID wid = chunk.getWorld().getUID();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        int count = 0;

        Iterator<Map.Entry<String, NodeData>> it = nodes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, NodeData> e = it.next();
            String[] p = e.getKey().split(":");
            if (p.length != 4) continue;
            UUID w = UUID.fromString(p[0]);
            if (!w.equals(wid)) continue;
            int x = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[3]);
            if ((x >> 4) == cx && (z >> 4) == cz) {
                int y = Integer.parseInt(p[2]);
                Location loc = new Location(chunk.getWorld(), x, y, z);
                if (loc.getBlock().getType() != Material.BEDROCK) {
                    loc.getBlock().setType(Material.DEEPSLATE, false);
                }
                it.remove();
                count++;
            }
        }
        return count;
    }

    public int removeRespawnsInChunk(Chunk chunk) {
        UUID wid = chunk.getWorld().getUID();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        int count = 0;

        Iterator<Map.Entry<String, RespawnData>> it = respawns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RespawnData> e = it.next();
            String[] p = e.getKey().split(":");
            if (p.length != 4) continue;
            UUID w = UUID.fromString(p[0]);
            if (!w.equals(wid)) continue;
            int x = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[3]);
            if ((x >> 4) == cx && (z >> 4) == cz) {
                it.remove();
                count++;
            }
        }
        return count;
    }

    public void scheduleRespawn(Location loc, Material oreType) {
        if (!plugin.getConfig().getBoolean("respawn.enabled", true)) return;
        long delaySec = plugin.getConfig().getLong("respawn.delay-seconds", 3600L);
        long due = System.currentTimeMillis() + delaySec * 1000L;
        respawns.put(key(loc), new RespawnData(oreType, due));
        save();
    }

    public void tickRespawns() {
        if (respawns.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, RespawnData> e : respawns.entrySet()) {
            if (e.getValue().dueAtMillis <= now) ready.add(e.getKey());
        }
        if (ready.isEmpty()) return;

        for (String k : ready) {
            Location loc = locationFromKey(k);
            RespawnData rd = respawns.get(k);
            if (loc == null || rd == null) { respawns.remove(k); continue; }
            World w = loc.getWorld();
            if (w == null) { respawns.remove(k); continue; }
            if (!loc.getChunk().isLoaded()) {
                boolean loaded = loc.getChunk().load();
                if (!loaded) continue;
            }
            int hits = randomHits();
            addNode(loc, rd.oreMaterial, hits);
            respawns.remove(k);
        }
        save();
    }

    private Location locationFromKey(String k) {
        try {
            String[] parts = k.split(":");
            UUID world = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            World w = Bukkit.getWorld(world);
            if (w == null) return null;
            return new Location(w, x, y, z);
        } catch (Exception e) {
            return null;
        }
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
                    if (loc.getBlock().getType() != mat) loc.getBlock().setType(mat, false);
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

        ConfigurationSection respSec = yml.getConfigurationSection("respawns");
        if (respSec != null) {
            for (String id : respSec.getKeys(false)) {
                ConfigurationSection s = respSec.getConfigurationSection(id);
                try {
                    UUID worldId = UUID.fromString(Objects.requireNonNull(s.getString("world")));
                    int x = s.getInt("x");
                    int y = s.getInt("y");
                    int z = s.getInt("z");
                    Material mat = Material.valueOf(s.getString("type"));
                    long due = s.getLong("dueAt");

                    World w = Bukkit.getWorld(worldId);
                    if (w == null) continue;
                    Location loc = new Location(w, x, y, z);
                    respawns.put(key(loc), new RespawnData(mat, due));
                } catch (Exception e) {
                    plugin.getLogger().warning("Bad respawn entry: " + id + " -> " + e.getMessage());
                }
            }
        }

        plugin.getLogger().info("Loaded nodes=" + nodes.size() + ", respawns=" + respawns.size() + ", processed worlds=" + processedChunks.size());
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

        int r = 0;
        for (Map.Entry<String, RespawnData> e : respawns.entrySet()) {
            String id = "r" + (r++);
            String[] parts = e.getKey().split(":");
            UUID world = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);

            RespawnData rd = e.getValue();
            String path = "respawns." + id;
            yml.set(path + ".world", world.toString());
            yml.set(path + ".x", x);
            yml.set(path + ".y", y);
            yml.set(path + ".z", z);
            yml.set(path + ".type", rd.oreMaterial.name());
            yml.set(path + ".dueAt", rd.dueAtMillis);
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
