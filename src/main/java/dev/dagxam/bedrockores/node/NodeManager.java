package dev.dagxam.bedrockores.node;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Runtime registry for generated ore nodes and respawns.
 *
 * Design goals for v2:
 * - keep the existing public API used by commands/listeners;
 * - avoid scanning every node when operating on one chunk;
 * - never force-load chunks during respawn processing;
 * - keep the existing nodes.yml format readable for migration compatibility.
 */
public final class NodeManager {
    private final Plugin plugin;
    private final Random rnd = new Random();

    // Persistent/runtime node state. String keys are retained for nodes.yml compatibility.
    private final Map<String, NodeData> nodes = new HashMap<>();

    // Kept for compatibility with the current generation command flow.
    // GenerationListener will remove this dependency in the deterministic-generator phase.
    private final Map<UUID, Set<Long>> processedChunks = new HashMap<>();

    private final Map<String, RespawnData> respawns = new HashMap<>();

    // Fast chunk indexes. Values are node/respawn keys, not Bukkit Locations.
    private final Map<UUID, Map<Long, Set<String>>> nodesByChunk = new HashMap<>();
    private final Map<UUID, Map<Long, Set<String>>> respawnsByChunk = new HashMap<>();

    private final File dataFile;
    private final Set<String> warnedDisplayMaterials = new HashSet<>();

    public NodeManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "nodes.yml");
    }

    public static String key(Location loc) {
        Objects.requireNonNull(loc, "loc");
        Objects.requireNonNull(loc.getWorld(), "loc.world");
        return key(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static String key(UUID worldId, int x, int y, int z) {
        return worldId + ":" + x + ":" + y + ":" + z;
    }

    public static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    public boolean isNode(UUID worldId, int x, int y, int z) {
        return nodes.containsKey(key(worldId, x, y, z));
    }

    public boolean isNode(Location loc) {
        return loc != null && loc.getWorld() != null && nodes.containsKey(key(loc));
    }

    public NodeData getNode(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return nodes.get(key(loc));
    }

    /**
     * Adds a runtime node and updates the chunk index atomically from the main thread.
     */
    public void addNode(Location loc, Material oreMaterial, int hits) {
        if (loc == null || loc.getWorld() == null || oreMaterial == null) return;

        int safeHits = Math.max(1, hits);
        String nodeKey = key(loc);

        Material baseBlock = oreMaterial == Material.NETHERITE_SCRAP
                ? Material.ANCIENT_DEBRIS
                : oreMaterial;

        NodeData nd = new NodeData(oreMaterial, safeHits, safeHits);
        NodeData previous = nodes.put(nodeKey, nd);
        if (previous == null) {
            indexAdd(loc);
        }

        Material toPlace = baseBlock;
        if (serverSolidEnabled()) {
            Material display = displayFor(oreMaterial);
            if (display != null) toPlace = display;
        }

        loc.getBlock().setType(toPlace, false);
    }

    public void removeNode(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        String nodeKey = key(loc);
        if (nodes.remove(nodeKey) != null) {
            indexRemove(loc);
        }
    }

    public void markChunkProcessed(World world, int cx, int cz) {
        if (world == null) return;
        processedChunks.computeIfAbsent(world.getUID(), k -> new HashSet<>()).add(chunkKey(cx, cz));
    }

    public boolean isChunkProcessed(World world, int cx, int cz) {
        if (world == null) return false;
        Set<Long> set = processedChunks.get(world.getUID());
        return set != null && set.contains(chunkKey(cx, cz));
    }

    public void clearProcessedFlags(World world) {
        if (world != null) processedChunks.remove(world.getUID());
    }

    public void clearAllProcessedFlags() {
        processedChunks.clear();
    }

    /** Removes only nodes indexed in this chunk; no global scan. */
    public int removeNodesInChunk(Chunk chunk) {
        if (chunk == null) return 0;
        UUID wid = chunk.getWorld().getUID();
        long ck = chunkKey(chunk.getX(), chunk.getZ());
        Map<Long, Set<String>> byChunk = nodesByChunk.get(wid);
        if (byChunk == null) return 0;

        Set<String> keys = byChunk.remove(ck);
        if (keys == null || keys.isEmpty()) return 0;

        int removed = 0;
        for (String nodeKey : new ArrayList<>(keys)) {
            if (nodes.remove(nodeKey) != null) removed++;
        }
        if (byChunk.isEmpty()) nodesByChunk.remove(wid);
        return removed;
    }

    /** Removes only respawns indexed in this chunk; no global scan. */
    public int removeRespawnsInChunk(Chunk chunk) {
        if (chunk == null) return 0;
        UUID wid = chunk.getWorld().getUID();
        long ck = chunkKey(chunk.getX(), chunk.getZ());
        Map<Long, Set<String>> byChunk = respawnsByChunk.get(wid);
        if (byChunk == null) return 0;

        Set<String> keys = byChunk.remove(ck);
        if (keys == null || keys.isEmpty()) return 0;

        int removed = 0;
        for (String respawnKey : new ArrayList<>(keys)) {
            if (respawns.remove(respawnKey) != null) removed++;
        }
        if (byChunk.isEmpty()) respawnsByChunk.remove(wid);
        return removed;
    }

    public void scheduleRespawn(Location loc, Material oreType) {
        if (loc == null || loc.getWorld() == null || oreType == null) return;
        if (!plugin.getConfig().getBoolean("respawn.enabled", true)) return;

        long delaySec = Math.max(0L, plugin.getConfig().getLong("respawn.delay-seconds", 3600L));
        long due = System.currentTimeMillis() + delaySec * 1000L;
        String k = key(loc);

        // Replace an existing schedule without duplicating the chunk index entry.
        respawns.put(k, new RespawnData(oreType, due));
        indexRespawnAdd(loc);

        if (plugin.getConfig().getBoolean("persistence.save-on-respawn-schedule", false)) {
            save();
        }
    }

    /** Processes due respawns without ever loading an unloaded chunk. */
    public void tickRespawns() {
        if (respawns.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (Map.Entry<String, RespawnData> e : new ArrayList<>(respawns.entrySet())) {
            RespawnData rd = e.getValue();
            if (!rd.isDue(now)) continue;

            Location loc = locationFromKey(e.getKey());
            if (loc == null || loc.getWorld() == null) {
                removeRespawnKey(e.getKey());
                continue;
            }

            World world = loc.getWorld();
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            if (!world.isChunkLoaded(cx, cz)) continue;

            addNode(loc, rd.oreMaterial, randomHits());
            removeRespawnKey(e.getKey());
        }
    }

    /** Processes only respawns belonging to the supplied, already loaded chunk. */
    public void processDueRespawnsInChunk(Chunk chunk) {
        if (chunk == null || respawns.isEmpty()) return;

        UUID wid = chunk.getWorld().getUID();
        long ck = chunkKey(chunk.getX(), chunk.getZ());
        Map<Long, Set<String>> byChunk = respawnsByChunk.get(wid);
        if (byChunk == null) return;

        Set<String> keys = byChunk.get(ck);
        if (keys == null || keys.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (String k : new ArrayList<>(keys)) {
            RespawnData rd = respawns.get(k);
            if (rd == null) {
                keys.remove(k);
                continue;
            }
            if (!rd.isDue(now)) continue;

            Location loc = locationFromKey(k);
            if (loc == null) {
                removeRespawnKey(k);
                continue;
            }

            addNode(loc, rd.oreMaterial, randomHits());
            removeRespawnKey(k);
        }

        if (keys.isEmpty()) {
            byChunk.remove(ck);
            if (byChunk.isEmpty()) respawnsByChunk.remove(wid);
        }
    }

    private void removeRespawnKey(String k) {
        RespawnData removed = respawns.remove(k);
        if (removed == null) return;
        String[] p = splitKey(k);
        if (p == null) return;
        UUID wid = UUID.fromString(p[0]);
        int x = Integer.parseInt(p[1]);
        int z = Integer.parseInt(p[3]);
        removeFromIndex(respawnsByChunk, wid, chunkKey(x >> 4, z >> 4), k);
    }

    private Location locationFromKey(String k) {
        String[] p = splitKey(k);
        if (p == null) return null;
        try {
            UUID worldId = UUID.fromString(p[0]);
            World world = Bukkit.getWorld(worldId);
            if (world == null) return null;
            return new Location(world,
                    Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String[] splitKey(String k) {
        if (k == null) return null;
        String[] p = k.split(":", -1);
        return p.length == 4 ? p : null;
    }

    // ===== LOAD =====

    public void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);

        nodes.clear();
        respawns.clear();
        processedChunks.clear();
        nodesByChunk.clear();
        respawnsByChunk.clear();

        ConfigurationSection nodesSec = yml.getConfigurationSection("nodes");
        if (nodesSec != null) {
            for (String id : nodesSec.getKeys(false)) {
                ConfigurationSection s = nodesSec.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    UUID worldId = UUID.fromString(Objects.requireNonNull(s.getString("world")));
                    int x = s.getInt("x");
                    int y = s.getInt("y");
                    int z = s.getInt("z");
                    Material type = Material.valueOf(Objects.requireNonNull(s.getString("type")));
                    int hits = s.getInt("hits");
                    int maxHits = s.getInt("maxHits", hits);
                    nodes.put(key(worldId, x, y, z), new NodeData(type, hits, maxHits));
                } catch (Exception ignored) {
                    // Ignore malformed individual records so one bad node cannot break startup.
                }
            }
        }

        ConfigurationSection pc = yml.getConfigurationSection("processedChunks");
        if (pc != null) {
            for (String w : pc.getKeys(false)) {
                try {
                    UUID worldId = UUID.fromString(w);
                    Set<Long> set = new HashSet<>();
                    for (String entry : pc.getStringList(w)) {
                        String[] p = entry.split(":", -1);
                        if (p.length != 2) continue;
                        set.add(chunkKey(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
                    }
                    processedChunks.put(worldId, set);
                } catch (Exception ignored) {}
            }
        }

        ConfigurationSection rs = yml.getConfigurationSection("respawns");
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection s = rs.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    UUID worldId = UUID.fromString(Objects.requireNonNull(s.getString("world")));
                    int x = s.getInt("x");
                    int y = s.getInt("y");
                    int z = s.getInt("z");
                    Material type = Material.valueOf(Objects.requireNonNull(s.getString("type")));
                    long dueAt = s.getLong("dueAt");
                    String k = key(worldId, x, y, z);
                    respawns.put(k, new RespawnData(type, dueAt));
                } catch (Exception ignored) {}
            }
        }

        rebuildIndex();
        plugin.getLogger().info("Loaded nodes=" + nodes.size()
                + ", respawns=" + respawns.size()
                + ", processed worlds=" + processedChunks.size());
    }

    // ===== SNAPSHOT API =====

    public record NodeEntry(UUID world, int x, int y, int z, Material type, int hits, int maxHits) {}
    public record RespawnEntry(UUID world, int x, int y, int z, Material type, long dueAtMillis) {}
    public record SaveSnapshot(List<NodeEntry> nodes,
                               Map<UUID, List<String>> processedChunks,
                               List<RespawnEntry> respawns) {}

    public SaveSnapshot createSnapshot() {
        List<NodeEntry> nodeList = new ArrayList<>(nodes.size());
        for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
            String[] p = splitKey(e.getKey());
            if (p == null) continue;
            try {
                UUID world = UUID.fromString(p[0]);
                NodeData nd = e.getValue();
                nodeList.add(new NodeEntry(world,
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]),
                        nd.oreMaterial,
                        nd.hitsRemaining,
                        nd.maxHits));
            } catch (RuntimeException ignored) {}
        }

        Map<UUID, List<String>> processed = new HashMap<>();
        for (Map.Entry<UUID, Set<Long>> e : processedChunks.entrySet()) {
            List<String> list = new ArrayList<>(e.getValue().size());
            for (Long ck : e.getValue()) {
                int cx = (int) (ck >> 32);
                int cz = (int) (ck.longValue());
                list.add(cx + ":" + cz);
            }
            processed.put(e.getKey(), list);
        }

        List<RespawnEntry> respawnList = new ArrayList<>(respawns.size());
        for (Map.Entry<String, RespawnData> e : respawns.entrySet()) {
            String[] p = splitKey(e.getKey());
            if (p == null) continue;
            try {
                UUID world = UUID.fromString(p[0]);
                RespawnData rd = e.getValue();
                respawnList.add(new RespawnEntry(world,
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]),
                        rd.oreMaterial,
                        rd.dueAtMillis));
            } catch (RuntimeException ignored) {}
        }

        return new SaveSnapshot(nodeList, processed, respawnList);
    }

    public void saveSnapshot(SaveSnapshot snapshot) {
        YamlConfiguration yml = new YamlConfiguration();

        int i = 0;
        for (NodeEntry n : snapshot.nodes()) {
            String path = "nodes.n" + (i++);
            yml.set(path + ".world", n.world().toString());
            yml.set(path + ".x", n.x());
            yml.set(path + ".y", n.y());
            yml.set(path + ".z", n.z());
            yml.set(path + ".type", n.type().name());
            yml.set(path + ".hits", n.hits());
            yml.set(path + ".maxHits", n.maxHits());
        }

        for (Map.Entry<UUID, List<String>> e : snapshot.processedChunks().entrySet()) {
            yml.set("processedChunks." + e.getKey(), e.getValue());
        }

        int r = 0;
        for (RespawnEntry rd : snapshot.respawns()) {
            String path = "respawns.r" + (r++);
            yml.set(path + ".world", rd.world().toString());
            yml.set(path + ".x", rd.x());
            yml.set(path + ".y", rd.y());
            yml.set(path + ".z", rd.z());
            yml.set(path + ".type", rd.type().name());
            yml.set(path + ".dueAt", rd.dueAtMillis());
        }

        try {
            File parent = dataFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder: " + parent);
            }
            yml.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save nodes.yml: " + ex.getMessage());
        }
    }

    public void save() {
        saveSnapshot(createSnapshot());
    }

    // ===== INDEX =====

    private void indexAdd(Location loc) {
        UUID wid = loc.getWorld().getUID();
        long ck = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        nodesByChunk
                .computeIfAbsent(wid, k -> new HashMap<>())
                .computeIfAbsent(ck, k -> new HashSet<>())
                .add(key(loc));
    }

    private void indexRemove(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        removeFromIndex(nodesByChunk,
                loc.getWorld().getUID(),
                chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4),
                key(loc));
    }

    private void indexRespawnAdd(Location loc) {
        UUID wid = loc.getWorld().getUID();
        long ck = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        respawnsByChunk
                .computeIfAbsent(wid, k -> new HashMap<>())
                .computeIfAbsent(ck, k -> new HashSet<>())
                .add(key(loc));
    }

    private static void removeFromIndex(Map<UUID, Map<Long, Set<String>>> index,
                                        UUID world,
                                        long chunk,
                                        String key) {
        Map<Long, Set<String>> byChunk = index.get(world);
        if (byChunk == null) return;
        Set<String> keys = byChunk.get(chunk);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) byChunk.remove(chunk);
        if (byChunk.isEmpty()) index.remove(world);
    }

    private void rebuildIndex() {
        nodesByChunk.clear();
        respawnsByChunk.clear();

        for (String k : nodes.keySet()) {
            String[] p = splitKey(k);
            if (p == null) continue;
            try {
                UUID world = UUID.fromString(p[0]);
                int x = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[3]);
                nodesByChunk
                        .computeIfAbsent(world, ignored -> new HashMap<>())
                        .computeIfAbsent(chunkKey(x >> 4, z >> 4), ignored -> new HashSet<>())
                        .add(k);
            } catch (RuntimeException ignored) {}
        }

        for (String k : respawns.keySet()) {
            String[] p = splitKey(k);
            if (p == null) continue;
            try {
                UUID world = UUID.fromString(p[0]);
                int x = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[3]);
                respawnsByChunk
                        .computeIfAbsent(world, ignored -> new HashMap<>())
                        .computeIfAbsent(chunkKey(x >> 4, z >> 4), ignored -> new HashSet<>())
                        .add(k);
            } catch (RuntimeException ignored) {}
        }
    }

    // ===== GAMEPLAY / VISUALS =====

    private int randomHits() {
        int min = Math.max(1, plugin.getConfig().getInt("node.hits-min", 9));
        int max = Math.max(min, plugin.getConfig().getInt("node.hits-max", 20));
        return min + rnd.nextInt(max - min + 1);
    }

    private boolean serverSolidEnabled() {
        return plugin.getConfig().getBoolean("visual.server-solid.enabled", false);
    }

    private Material displayFor(Material oreMaterial) {
        String path = "visual.server-solid.map." + oreMaterial.name();
        String raw = plugin.getConfig().getString(path);
        if (raw == null || raw.isBlank()) return null;

        Material display;
        try {
            display = Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            if (warnedDisplayMaterials.add(raw)) {
                plugin.getLogger().warning("Invalid visual material " + raw + " for " + oreMaterial);
            }
            return null;
        }

        if (!display.isBlock()) {
            if (warnedDisplayMaterials.add(raw)) {
                plugin.getLogger().warning("Visual material is not a block: " + raw + " for " + oreMaterial);
            }
            return null;
        }
        return display;
    }

    /** Apply configured solid visuals only to nodes in already loaded chunks. */
    public int applyServerVisualsInWorld(World world, boolean loadedChunksOnly) {
        if (world == null || !serverSolidEnabled()) return 0;
        int count = 0;
        Map<Long, Set<String>> byChunk = nodesByChunk.get(world.getUID());
        if (byChunk == null) return 0;

        for (Set<String> keys : new ArrayList<>(byChunk.values())) {
            for (String k : new ArrayList<>(keys)) {
                NodeData nd = nodes.get(k);
                if (nd == null) continue;
                String[] p = splitKey(k);
                if (p == null) continue;
                try {
                    int x = Integer.parseInt(p[1]);
                    int y = Integer.parseInt(p[2]);
                    int z = Integer.parseInt(p[3]);
                    int cx = x >> 4;
                    int cz = z >> 4;
                    if (loadedChunksOnly && !world.isChunkLoaded(cx, cz)) continue;
                    Material display = displayFor(nd.oreMaterial);
                    if (display == null) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != display) {
                        block.setType(display, false);
                    }
                    count++;
                } catch (RuntimeException ignored) {}
            }
        }
        return count;
    }
}
