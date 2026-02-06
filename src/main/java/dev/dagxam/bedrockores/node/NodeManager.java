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
    private final Random rnd = new Random();

    private final Map<String, NodeData> nodes = new HashMap<>();
    private final Map<String, RespawnData> respawns = new HashMap<>();
    private final Map<UUID, Set<Long>> processedChunks = new HashMap<>();
    private final Map<UUID, Map<Long, List<Location>>> nodesByChunk = new HashMap<>();

    private final File dataFile;
    private final Set<String> warnedDisplayMaterials = new HashSet<>();

    public NodeManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "nodes.yml");
    }

    // ===================== CONFIG =====================

    private int randomHits() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("node");

        int min = 9;
        int max = 20;

        if (sec != null) {
            min = sec.getInt("hits-min", min);
            max = sec.getInt("hits-max", max);
        }

        if (min < 1) min = 1;
        if (max < min) max = min;

        return min + rnd.nextInt((max - min) + 1);
    }

    // ===================== KEYS =====================

    public String key(Location loc) {
        return loc.getWorld().getUID() + ":" +
               loc.getBlockX() + ":" +
               loc.getBlockY() + ":" +
               loc.getBlockZ();
    }

    private long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    // ===================== MATERIALS =====================

    public Material pickOreMaterial() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("ore-weights");
        if (sec == null) return Material.COAL_ORE;

        double total = 0;
        for (String k : sec.getKeys(false)) {
            total += sec.getDouble(k);
        }

        if (total <= 0) return Material.COAL_ORE;

        double r = rnd.nextDouble() * total;
        double acc = 0;

        for (String k : sec.getKeys(false)) {
            acc += sec.getDouble(k);
            if (r <= acc) {
                Material m = Material.matchMaterial(k);
                return m != null ? m : Material.COAL_ORE;
            }
        }
        return Material.COAL_ORE;
    }

    public Material getDisplayMaterial(Material oreMaterial) {
        String path = "display-materials." + oreMaterial.name();
        String name = plugin.getConfig().getString(path);

        if (name == null) return oreMaterial;

        Material m = Material.matchMaterial(name);
        if (m == null) {
            if (warnedDisplayMaterials.add(path)) {
                plugin.getLogger().warning("Invalid display material: " + name);
            }
            return oreMaterial;
        }
        return m;
    }

    // ===================== NODES =====================

    public boolean isNode(Location loc) {
        return nodes.containsKey(key(loc));
    }

    public NodeData getNode(Location loc) {
        return nodes.get(key(loc));
    }

    public void addNode(Location loc, Material ore, int hits) {
        nodes.put(key(loc), new NodeData(ore, hits));
        loc.getBlock().setType(getDisplayMaterial(ore), false);
        indexNode(loc);
    }

    public void removeNode(Location loc) {
        nodes.remove(key(loc));
        unindexNode(loc);
    }

    public void decrementHits(Location loc) {
        NodeData nd = nodes.get(key(loc));
        if (nd == null) return;

        nd.hitsLeft--;
        if (nd.hitsLeft <= 0) {
            Material ore = nd.oreMaterial;
            removeNode(loc);
            loc.getBlock().setType(Material.BEDROCK, false);
            scheduleRespawn(loc, ore);
        }
    }

    // ===================== RESPAWN =====================

    public void scheduleRespawn(Location loc, Material ore) {
        int min = plugin.getConfig().getInt("respawn.min-seconds", 300);
        int max = plugin.getConfig().getInt("respawn.max-seconds", 900);
        if (max < min) max = min;

        long due = System.currentTimeMillis() +
                (min + rnd.nextInt(max - min + 1)) * 1000L;

        respawns.put(key(loc), new RespawnData(ore, due));
    }

    public void tickRespawns() {
        if (respawns.isEmpty()) return;

        long now = System.currentTimeMillis();
        List<String> done = new ArrayList<>();

        for (Map.Entry<String, RespawnData> e : new ArrayList<>(respawns.entrySet())) {
            if (e.getValue().dueAtMillis > now) continue;

            Location loc = locationFromKey(e.getKey());
            if (loc == null) { done.add(e.getKey()); continue; }

            World w = loc.getWorld();
            if (w == null) { done.add(e.getKey()); continue; }

            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            if (!w.isChunkLoaded(cx, cz)) continue;

            addNode(loc, e.getValue().oreMaterial, randomHits());
            done.add(e.getKey());
        }

        done.forEach(respawns::remove);
    }

    // ===================== INDEX =====================

    private void indexNode(Location loc) {
        UUID w = loc.getWorld().getUID();
        long ck = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);

        nodesByChunk
                .computeIfAbsent(w, __ -> new HashMap<>())
                .computeIfAbsent(ck, __ -> new ArrayList<>())
                .add(loc);
    }

    private void unindexNode(Location loc) {
        UUID w = loc.getWorld().getUID();
        Map<Long, List<Location>> map = nodesByChunk.get(w);
        if (map == null) return;

        long ck = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        List<Location> list = map.get(ck);
        if (list == null) return;

        list.removeIf(l ->
                l.getBlockX() == loc.getBlockX() &&
                l.getBlockY() == loc.getBlockY() &&
                l.getBlockZ() == loc.getBlockZ()
        );
    }

    // ===================== UTILS =====================

    private Location locationFromKey(String k) {
        try {
            String[] p = k.split(":");
            World w = Bukkit.getWorld(UUID.fromString(p[0]));
            if (w == null) return null;
            return new Location(w,
                    Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]));
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== LOAD / SAVE =====================

    public void load() {
        nodes.clear();
        respawns.clear();
        processedChunks.clear();
        nodesByChunk.clear();

        if (!dataFile.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection ns = yml.getConfigurationSection("nodes");
        if (ns != null) {
            for (String k : ns.getKeys(false)) {
                Material m = Material.matchMaterial(ns.getString(k + ".ore"));
                int hits = ns.getInt(k + ".hits", randomHits());
                Location loc = locationFromKey(k);
                if (loc == null || m == null) continue;
                nodes.put(k, new NodeData(m, hits));
                indexNode(loc);
            }
        }

        ConfigurationSection rs = yml.getConfigurationSection("respawns");
        if (rs != null) {
            for (String k : rs.getKeys(false)) {
                Material m = Material.matchMaterial(rs.getString(k + ".ore"));
                long due = rs.getLong(k + ".due");
                if (m != null) respawns.put(k, new RespawnData(m, due));
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();

        ConfigurationSection ns = yml.createSection("nodes");
        nodes.forEach((k, v) -> {
            ns.set(k + ".ore", v.oreMaterial.name());
            ns.set(k + ".hits", v.hitsLeft);
        });

        ConfigurationSection rs = yml.createSection("respawns");
        respawns.forEach((k, v) -> {
            rs.set(k + ".ore", v.oreMaterial.name());
            rs.set(k + ".due", v.dueAtMillis);
        });

        try {
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save nodes.yml");
        }
    }
}
