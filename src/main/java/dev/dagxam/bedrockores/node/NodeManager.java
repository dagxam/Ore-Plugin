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

    // Активные узлы: key(worldUUID:x:y:z) -> NodeData
    private final Map<String, NodeData> nodes = new HashMap<>();
    // Обработанные чанки (чтобы не генерировать повторно)
    private final Map<UUID, Set<Long>> processedChunks = new HashMap<>();
    // Очередь респаунов
    private final Map<String, RespawnData> respawns = new HashMap<>();
    // Индекс узлов по чанкам
    private final Map<UUID, Map<Long, List<Location>>> nodesByChunk = new HashMap<>();

    private final File dataFile;

    // анти-спам в лог: запоминаем уже предупреждённые ключи
    private final Set<String> warnedDisplayMaterials = new HashSet<>();

    public NodeManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "nodes.yml");
    }

    public Plugin getPlugin() { return plugin; }

    // ===== KEY UTILS =====

    public String key(Location loc) {
        return loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    // ===== CONFIG HELPERS =====

    private int randomHits() {
        int min = plugin.getConfig().getInt("min-hits", 15);
        int max = plugin.getConfig().getInt("max-hits", 25);
        if (max < min) max = min;
        return min + rnd.nextInt((max - min) + 1);
    }

    public Material pickOreMaterial() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("ore-weights");
        if (sec == null) return Material.COAL_ORE;

        double total = 0.0;
        for (String k : sec.getKeys(false)) total += sec.getDouble(k);

        if (total <= 0.0) return Material.COAL_ORE;

        double r = rnd.nextDouble() * total;
        double cum = 0.0;

        for (String k : sec.getKeys(false)) {
            cum += sec.getDouble(k);
            if (r <= cum) {
                Material m = Material.matchMaterial(k);
                return (m != null) ? m : Material.COAL_ORE;
            }
        }
        return Material.COAL_ORE;
    }

    public Material getDisplayMaterial(Material oreMaterial) {
        String path = "display-materials." + oreMaterial.name();
        String matName = plugin.getConfig().getString(path, null);
        if (matName == null || matName.isEmpty()) return oreMaterial;

        Material m = Material.matchMaterial(matName);
        if (m == null) {
            if (warnedDisplayMaterials.add(path)) {
                plugin.getLogger().warning("Invalid display material '" + matName + "' for " + oreMaterial + " in " + path);
            }
            return oreMaterial;
        }
        return m;
    }

    // ===== INDEX =====

    private void indexNode(Location loc) {
        UUID w = loc.getWorld().getUID();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        long ck = chunkKey(cx, cz);

        nodesByChunk.computeIfAbsent(w, __ -> new HashMap<>());
        Map<Long, List<Location>> map = nodesByChunk.get(w);
        map.computeIfAbsent(ck, __ -> new ArrayList<>());
        map.get(ck).add(loc);
    }

    private void unindexNode(Location loc) {
        UUID w = loc.getWorld().getUID();
        Map<Long, List<Location>> map = nodesByChunk.get(w);
        if (map == null) return;

        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        long ck = chunkKey(cx, cz);

        List<Location> list = map.get(ck);
        if (list == null) return;

        list.removeIf(l -> l.getBlockX() == loc.getBlockX()
                && l.getBlockY() == loc.getBlockY()
                && l.getBlockZ() == loc.getBlockZ());

        if (list.isEmpty()) map.remove(ck);
        if (map.isEmpty()) nodesByChunk.remove(w);
    }

    // ===== NODES =====

    public NodeData getNode(Location loc) {
        return nodes.get(key(loc));
    }

    public boolean isNode(Location loc) {
        return nodes.containsKey(key(loc));
    }

    public void addNode(Location loc, Material oreType, int hitsLeft) {
        String k = key(loc);
        nodes.put(k, new NodeData(oreType, hitsLeft));

        // выставляем блок
        Material display = getDisplayMaterial(oreType);
        loc.getBlock().setType(display, false);

        indexNode(loc);

        if (plugin.getConfig().getBoolean("persistence.save-on-node-add", false)) {
            save();
        }
    }

    public void removeNode(Location loc) {
        String k = key(loc);
        if (!nodes.containsKey(k)) return;

        nodes.remove(k);
        unindexNode(loc);

        if (plugin.getConfig().getBoolean("persistence.save-on-node-remove", false)) {
            save();
        }
    }

    public void decrementHits(Location loc) {
        String k = key(loc);
        NodeData nd = nodes.get(k);
        if (nd == null) return;

        nd.hitsLeft--;
        if (nd.hitsLeft <= 0) {
            // исчерпан -> бедрок и респаун
            Material oreType = nd.oreMaterial;
            removeNode(loc);

            loc.getBlock().setType(Material.BEDROCK, false);
            scheduleRespawn(loc, oreType);
        } else {
            if (plugin.getConfig().getBoolean("persistence.save-on-hit", false)) {
                save();
            }
        }
    }

    public int getHitsLeft(Location loc) {
        NodeData nd = nodes.get(key(loc));
        return nd != null ? nd.hitsLeft : 0;
    }

    // ===== RESPAWNS =====

    public void scheduleRespawn(Location loc, Material oreType) {
        int min = plugin.getConfig().getInt("respawn.min-seconds", 300);
        int max = plugin.getConfig().getInt("respawn.max-seconds", 900);
        if (max < min) max = min;
        int delaySec = min + rnd.nextInt((max - min) + 1);

        long due = System.currentTimeMillis() + delaySec * 1000L;
        respawns.put(key(loc), new RespawnData(oreType, due));

        if (plugin.getConfig().getBoolean("persistence.save-on-respawn-schedule", false)) {
            save();
        }
    }

    public void tickRespawns() {
        if (respawns.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<String> done = new ArrayList<>();

        // Итерация по "снимку", чтобы не падать при ре-энтрантных модификациях карты
        for (Map.Entry<String, RespawnData> e : new ArrayList<>(respawns.entrySet())) {
            RespawnData rd = e.getValue();
            if (rd.dueAtMillis > now) continue;

            Location loc = locationFromKey(e.getKey());
            if (loc == null) { done.add(e.getKey()); continue; }

            World w = loc.getWorld();
            if (w == null) { done.add(e.getKey()); continue; }

            // ВАЖНО: не используем loc.getChunk(), чтобы не триггерить загрузку чанка и ChunkLoadEvent
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            if (!w.isChunkLoaded(cx, cz)) continue;

            addNode(loc, rd.oreMaterial, randomHits());
            done.add(e.getKey());
        }

        for (String k : done) respawns.remove(k);
    }

    public void processDueRespawnsInChunk(Chunk chunk) {
        if (respawns.isEmpty()) return;
        long now = System.currentTimeMillis();
        UUID wid = chunk.getWorld().getUID();
        int cx = chunk.getX(), cz = chunk.getZ();

        List<String> done = new ArrayList<>();

        // Итерация по "снимку" на случай, если во время вызова (например, в ChunkLoadEvent)
        // карта respawns модифицируется вложенно.
        for (Map.Entry<String, RespawnData> e : new ArrayList<>(respawns.entrySet())) {
            String[] p = e.getKey().split(":");
            if (p.length != 4) continue;

            UUID w;
            try { w = UUID.fromString(p[0]); }
            catch (Exception ex) { continue; }

            if (!w.equals(wid)) continue;

            int x = Integer.parseInt(p[1]);
            int y = Integer.parseInt(p[2]);
            int z = Integer.parseInt(p[3]);
            if ((x >> 4) != cx || (z >> 4) != cz) continue;

            RespawnData rd = e.getValue();
            if (rd.dueAtMillis > now) continue;

            Location loc = new Location(chunk.getWorld(), x, y, z);
            addNode(loc, rd.oreMaterial, randomHits());
            done.add(e.getKey());
        }

        for (String k : done) respawns.remove(k);
    }

    private Location locationFromKey(String k) {
        try {
            String[] parts = k.split(":");
            if (parts.length != 4) return null;
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

    // ===== LOAD =====

    public void load() {
        nodes.clear();
        respawns.clear();
        processedChunks.clear();
        nodesByChunk.clear();

        if (!dataFile.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection nodesSec = yml.getConfigurationSection("nodes");
        if (nodesSec != null) {
            for (String k : nodesSec.getKeys(false)) {
                String matName = nodesSec.getString(k + ".ore", "COAL_ORE");
                int hits = nodesSec.getInt(k + ".hits", randomHits());

                Material m = Material.matchMaterial(matName);
                if (m == null) m = Material.COAL_ORE;

                Location loc = locationFromKey(k);
                if (loc == null) continue;

                nodes.put(k, new NodeData(m, hits));
                indexNode(loc);
            }
        }

        ConfigurationSection respSec = yml.getConfigurationSection("respawns");
        if (respSec != null) {
            for (String k : respSec.getKeys(false)) {
                String matName = respSec.getString(k + ".ore", "COAL_ORE");
                long due = respSec.getLong(k + ".due", 0L);

                Material m = Material.matchMaterial(matName);
                if (m == null) m = Material.COAL_ORE;

                respawns.put(k, new RespawnData(m, due));
            }
        }

        ConfigurationSection procSec = yml.getConfigurationSection("processedChunks");
        if (procSec != null) {
            for (String widStr : procSec.getKeys(false)) {
                try {
                    UUID wid = UUID.fromString(widStr);
                    List<Long> list = (List<Long>) procSec.getList(widStr, new ArrayList<>());
                    if (list == null) continue;
                    processedChunks.put(wid, new HashSet<>(list));
                } catch (Exception ignored) {}
            }
        }
    }

    public void save() {
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }

        YamlConfiguration yml = new YamlConfiguration();

        ConfigurationSection nodesSec = yml.createSection("nodes");
        for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
            NodeData nd = e.getValue();
            nodesSec.set(e.getKey() + ".ore", nd.oreMaterial.name());
            nodesSec.set(e.getKey() + ".hits", nd.hitsLeft);
        }

        ConfigurationSection respSec = yml.createSection("respawns");
        for (Map.Entry<String, RespawnData> e : respawns.entrySet()) {
            RespawnData rd = e.getValue();
            respSec.set(e.getKey() + ".ore", rd.oreMaterial.name());
            respSec.set(e.getKey() + ".due", rd.dueAtMillis);
        }

        ConfigurationSection procSec = yml.createSection("processedChunks");
        for (Map.Entry<UUID, Set<Long>> e : processedChunks.entrySet()) {
            procSec.set(e.getKey().toString(), new ArrayList<>(e.getValue()));
        }

        try {
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save nodes.yml: " + e.getMessage());
        }
    }

    // ===== GENERATION / CHUNK FLAGS =====

    public boolean isChunkProcessed(World w, int cx, int cz) {
        UUID wid = w.getUID();
        Set<Long> set = processedChunks.get(wid);
        if (set == null) return false;
        return set.contains(chunkKey(cx, cz));
    }

    public void markChunkProcessed(World w, int cx, int cz) {
        UUID wid = w.getUID();
        processedChunks.computeIfAbsent(wid, __ -> new HashSet<>());
        processedChunks.get(wid).add(chunkKey(cx, cz));
    }

    public void clearProcessedFlags() {
        processedChunks.clear();
        save();
    }

    public List<Location> getNodesInChunk(Chunk chunk) {
        UUID wid = chunk.getWorld().getUID();
        Map<Long, List<Location>> map = nodesByChunk.get(wid);
        if (map == null) return Collections.emptyList();
        long ck = chunkKey(chunk.getX(), chunk.getZ());
        List<Location> list = map.get(ck);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }
}
