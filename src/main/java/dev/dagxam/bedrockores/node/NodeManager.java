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

    public int randomHits() {
        int min = plugin.getConfig().getInt("node.hits-min", 3);
        int max = plugin.getConfig().getInt("node.hits-max", 7);
        if (max < min) max = min;
        if (min < 1) min = 1;
        return min + rnd.nextInt((max - min) + 1);
    }

    public Material pickOreMaterial(World world) {
        boolean isNether = world.getEnvironment() == World.Environment.NETHER;

        String weightsKey = isNether ? "ore-weights-nether" : "ore-weights";
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(weightsKey);
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

    public Material getServerSolidMaterial(Material oreMaterial) {
        String key = "visual.server-solid.map." + oreMaterial.name();
        String matName = plugin.getConfig().getString(key, null);
        if (matName == null) return oreMaterial;

        Material m = Material.matchMaterial(matName);
        if (m == null) {
            if (warnedDisplayMaterials.add(key)) {
                plugin.getLogger().warning("Invalid visual.server-solid.map material '" + matName + "' for " + oreMaterial + " (" + key + ")");
            }
            return oreMaterial;
        }
        return m;
    }

    public boolean serverSolidEnabled() {
        return plugin.getConfig().getBoolean("visual.server-solid.enabled", false);
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

    // перегрузка, которую использует генератор
    public boolean isNode(UUID worldId, int x, int y, int z) {
        World w = Bukkit.getWorld(worldId);
        if (w == null) return false;
        return isNode(new Location(w, x, y, z));
    }

    public void addNode(Location loc, Material oreType, int maxHits) {
        String k = key(loc);
        nodes.put(k, new NodeData(oreType, maxHits, maxHits));

        // выставляем блок (если server-solid включён — ставим "маску")
        Material placeMat = serverSolidEnabled() ? getServerSolidMaterial(oreType) : oreType;
        loc.getBlock().setType(placeMat, false);

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

    public int hitNode(Location loc) {
        NodeData nd = nodes.get(key(loc));
        if (nd == null) return 0;

        nd.hitsRemaining--;
        if (nd.hitsRemaining <= 0) {
            // исчерпан -> бедрок и респаун
            Material oreType = nd.oreMaterial;
            removeNode(loc);

            loc.getBlock().setType(Material.BEDROCK, false);

            if (plugin.getConfig().getBoolean("respawn.enabled", true)) {
                scheduleRespawn(loc, oreType);
            }
            return 0;
        }
        return nd.hitsRemaining;
    }

    public int getMaxHits(Location loc) {
        NodeData nd = nodes.get(key(loc));
        return nd != null ? nd.maxHits : 0;
    }

    public int getHitsRemaining(Location loc) {
        NodeData nd = nodes.get(key(loc));
        return nd != null ? nd.hitsRemaining : 0;
    }

    // ===== RESPAWNS =====

    public void scheduleRespawn(Location loc, Material oreType) {
        long delaySeconds = plugin.getConfig().getLong("respawn.delay-seconds", 300L);
        long due = System.currentTimeMillis() + delaySeconds * 1000L;

        respawns.put(key(loc), new RespawnData(oreType, due));

        if (plugin.getConfig().getBoolean("persistence.save-on-respawn-schedule", false)) {
            save();
        }
    }

    /** FIXED: no CME + не триггерим загрузку чанков из tick-а */
    public void tickRespawns() {
        if (respawns.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<String> done = new ArrayList<>();

        // Итерация по "снимку", чтобы не падать при ре-энтрантных изменениях respawns
        for (Map.Entry<String, RespawnData> e : new ArrayList<>(respawns.entrySet())) {
            RespawnData rd = e.getValue();
            if (rd.dueAtMillis > now) continue;

            Location loc = locationFromKey(e.getKey());
            if (loc == null) { done.add(e.getKey()); continue; }
            World w = loc.getWorld();
            if (w == null) { done.add(e.getKey()); continue; }

            // ВАЖНО: не используем loc.getChunk() (может загрузить чанк и вызвать ChunkLoadEvent)
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

        // Итерация по "снимку" на случай ре-энтранта
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

    // ===== LOAD/SAVE =====

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
                int remaining = nodesSec.getInt(k + ".remaining", 1);
                int max = nodesSec.getInt(k + ".max", 1);

                Material m = Material.matchMaterial(matName);
                if (m == null) m = Material.COAL_ORE;

                Location loc = locationFromKey(k);
                if (loc == null) continue;

                nodes.put(k, new NodeData(m, remaining, max));
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
                    @SuppressWarnings("unchecked")
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
            nodesSec.set(e.getKey() + ".remaining", nd.hitsRemaining);
            nodesSec.set(e.getKey() + ".max", nd.maxHits);
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

    // ===== CHUNK FLAGS / COMMAND HELPERS =====

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

    public void clearProcessedFlags(World w) {
        processedChunks.remove(w.getUID());
    }

    public void removeNodesInChunk(Chunk chunk) {
        List<Location> in = getNodesInChunk(chunk);
        for (Location loc : in) {
            removeNode(loc);
            // аккуратно: блоки оставляем как есть (команда для регена решает дальше)
        }
    }

    public void removeRespawnsInChunk(Chunk chunk) {
        UUID wid = chunk.getWorld().getUID();
        int cx = chunk.getX(), cz = chunk.getZ();

        List<String> remove = new ArrayList<>();
        for (String k : respawns.keySet()) {
            String[] p = k.split(":");
            if (p.length != 4) continue;
            UUID w;
            try { w = UUID.fromString(p[0]); } catch (Exception ex) { continue; }
            if (!w.equals(wid)) continue;

            int x = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[3]);
            if ((x >> 4) != cx || (z >> 4) != cz) continue;

            remove.add(k);
        }
        for (String k : remove) respawns.remove(k);
    }

    public List<Location> getNodesInChunk(Chunk chunk) {
        UUID wid = chunk.getWorld().getUID();
        Map<Long, List<Location>> map = nodesByChunk.get(wid);
        if (map == null) return Collections.emptyList();
        long ck = chunkKey(chunk.getX(), chunk.getZ());
        List<Location> list = map.get(ck);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    // ===== VISUALS =====

    public void applyServerVisualsForAllNodes(boolean force) {
        if (!serverSolidEnabled() && !force) return;

        for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
            Location loc = locationFromKey(e.getKey());
            if (loc == null) continue;
            NodeData nd = e.getValue();

            Material placeMat = serverSolidEnabled() ? getServerSolidMaterial(nd.oreMaterial) : nd.oreMaterial;
            loc.getBlock().setType(placeMat, false);
        }
    }

    public void applyServerVisualsInWorld(World world, boolean force) {
        if (!serverSolidEnabled() && !force) return;

        UUID wid = world.getUID();
        for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(wid.toString() + ":")) continue;

            Location loc = locationFromKey(k);
            if (loc == null) continue;

            NodeData nd = e.getValue();
            Material placeMat = serverSolidEnabled() ? getServerSolidMaterial(nd.oreMaterial) : nd.oreMaterial;
            loc.getBlock().setType(placeMat, false);
        }
    }

    // ===== SNAPSHOT =====

    public static class SaveSnapshot {
        public final int nodesCount;
        public final int respawnsCount;
        public final int processedChunksCount;

        public SaveSnapshot(int nodesCount, int respawnsCount, int processedChunksCount) {
            this.nodesCount = nodesCount;
            this.respawnsCount = respawnsCount;
            this.processedChunksCount = processedChunksCount;
        }
    }

    public SaveSnapshot createSnapshot() {
        int pc = 0;
        for (Set<Long> s : processedChunks.values()) pc += s.size();
        return new SaveSnapshot(nodes.size(), respawns.size(), pc);
    }
}
