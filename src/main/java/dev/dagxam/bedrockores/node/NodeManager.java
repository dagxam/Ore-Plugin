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
    private final Map<UUID, Set<Long>> processedChunks = new HashMap<>();
    private final Map<String, RespawnData> respawns = new HashMap<>();
    private final Map<UUID, Map<Long, List<Location>>> nodesByChunk = new HashMap<>();

    private final File dataFile;

    // анти-спам в лог: запоминаем уже предупреждённые материалы
    private final Set<String> warnedDisplayMaterials = new HashSet<>();

    public NodeManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "nodes.yml");
    }

    public static String key(Location loc) {
        return loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public static String key(UUID worldId, int x, int y, int z) {
        return worldId + ":" + x + ":" + y + ":" + z;
    }

    public static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    /** Быстрая проверка без new Location (важно для генерации). */
    public boolean isNode(UUID worldId, int x, int y, int z) {
        return nodes.containsKey(key(worldId, x, y, z));
    }

    public boolean isNode(Location loc) { return nodes.containsKey(key(loc)); }

    public NodeData getNode(Location loc) { return nodes.get(key(loc)); }

    /**
     * Добавить узел.
     *
     * Важно про NETHERITE_SCRAP:
     * - в конфиге можно указать вес NETHERITE_SCRAP
     * - но это не блок, поэтому физически в мире ставим ANCIENT_DEBRIS
     * - в NodeData сохраняем именно NETHERITE_SCRAP, чтобы дропался scrap
     */
    public void addNode(Location loc, Material oreMaterial, int hits) {
        int max = hits;

        // если "руда" = NETHERITE_SCRAP, то базовый блок в мире = ANCIENT_DEBRIS
        Material baseBlock = oreMaterial;
        if (oreMaterial == Material.NETHERITE_SCRAP) {
            baseBlock = Material.ANCIENT_DEBRIS;
        }

        NodeData nd = new NodeData(oreMaterial, hits, max);
        nodes.put(key(loc), nd);

        Material toPlace = baseBlock;
        if (serverSolidEnabled()) {
            Material disp = displayFor(oreMaterial);
            if (disp != null) toPlace = disp;
        }

        loc.getBlock().setType(toPlace, false);
        indexAdd(loc);
    }

    public void removeNode(Location loc) {
        nodes.remove(key(loc));
        indexRemove(loc);
    }

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
        int cx = chunk.getX(), cz = chunk.getZ();
        int count = 0;

        Iterator<Map.Entry<String, NodeData>> it = nodes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, NodeData> e = it.next();
            String[] p = e.getKey().split(":");
            if (p.length != 4) continue;
            UUID w = UUID.fromString(p[0]);
            if (!w.equals(wid)) continue;

            int x = Integer.parseInt(p[1]);
            int y = Integer.parseInt(p[2]);
            int z = Integer.parseInt(p[3]);
            if ((x >> 4) == cx && (z >> 4) == cz) {
                Location loc = new Location(chunk.getWorld(), x, y, z);
                it.remove();
                indexRemove(loc);
                count++;
            }
        }
        return count;
    }

    public int removeRespawnsInChunk(Chunk chunk) {
        UUID wid = chunk.getWorld().getUID();
        int cx = chunk.getX(), cz = chunk.getZ();
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

        if (plugin.getConfig().getBoolean("persistence.save-on-respawn-schedule", false)) {
            save();
        }
    }

    public void tickRespawns() {
        if (respawns.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<String> done = new ArrayList<>();

        for (Map.Entry<String, RespawnData> e : respawns.entrySet()) {
            RespawnData rd = e.getValue();
            if (rd.dueAtMillis > now) continue;

            Location loc = locationFromKey(e.getKey());
            if (loc == null) { done.add(e.getKey()); continue; }
            World w = loc.getWorld();
            if (w == null) { done.add(e.getKey()); continue; }
            if (!loc.getChunk().isLoaded()) continue;

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
        for (Map.Entry<String, RespawnData> e : respawns.entrySet()) {
            String[] p = e.getKey().split(":");
            if (p.length != 4) continue;
            UUID w = UUID.fromString(p[0]);
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

    // ===== Load/Save =====

    public void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);

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
                } catch (Exception ignored) {}
            }
        }

        ConfigurationSection pc = yml.getConfigurationSection("processedChunks");
        if (pc != null) {
            for (String w : pc.getKeys(false)) {
                try {
                    UUID worldId = UUID.fromString(w);
                    List<String> list = pc.getStringList(w);
                    Set<Long> set = new HashSet<>();
                    for (String entry : list) {
                        String[] p = entry.split(":");
                        if (p.length != 2) continue;
                        int cx = Integer.parseInt(p[0]);
                        int cz = Integer.parseInt(p[1]);
                        set.add(chunkKey(cx, cz));
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
                    respawns.put(key(worldId, x, y, z), new RespawnData(type, dueAt));
                } catch (Exception ignored) {}
            }
        }

        rebuildIndex();
        plugin.getLogger().info("Loaded nodes=" + nodes.size() + ", respawns=" + respawns.size() + ", processed worlds=" + processedChunks.size());
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();

        int i = 0;
        for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
            String[] parts = e.getKey().split(":");
            if (parts.length != 4) continue;
            String path = "nodes.n" + (i++);

            yml.set(path + ".world", parts[0]);
            yml.set(path + ".x", Integer.parseInt(parts[1]));
            yml.set(path + ".y", Integer.parseInt(parts[2]));
            yml.set(path + ".z", Integer.parseInt(parts[3]));
            yml.set(path + ".type", e.getValue().oreMaterial.name());
            yml.set(path + ".hits", e.getValue().hitsRemaining);
            yml.set(path + ".maxHits", e.getValue().maxHits);
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
            String[] parts = e.getKey().split(":");
            if (parts.length != 4) continue;
            String path = "respawns.r" + (r++);

            yml.set(path + ".world", parts[0]);
            yml.set(path + ".x", Integer.parseInt(parts[1]));
            yml.set(path + ".y", Integer.parseInt(parts[2]));
            yml.set(path + ".z", Integer.parseInt(parts[3]));
            yml.set(path + ".type", e.getValue().oreMaterial.name());
            yml.set(path + ".dueAt", e.getValue().dueAtMillis);
        }

        try {
            yml.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save nodes.yml: " + ex.getMessage());
        }
    }

    // ===== Index =====

    private void rebuildIndex() {
        nodesByChunk.clear();
        for (String k : nodes.keySet()) {
            Location loc = locationFromKey(k);
            if (loc == null) continue;
            indexAdd(loc);
        }
    }

    private void indexAdd(Location loc) {
        UUID wid = loc.getWorld().getUID();
        long ck = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        nodesByChunk.computeIfAbsent(wid, w -> new HashMap<>())
                .computeIfAbsent(ck, c -> new ArrayList<>())
                .add(loc);
    }

    private void indexRemove(Location loc) {
        UUID wid = loc.getWorld().getUID();
        long ck = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        Map<Long, List<Location>> byChunk = nodesByChunk.get(wid);
        if (byChunk == null) return;
        List<Location> list = byChunk.get(ck);
        if (list == null) return;
        list.removeIf(l -> l.getBlockX() == loc.getBlockX()
                && l.getBlockY() == loc.getBlockY()
                && l.getBlockZ() == loc.getBlockZ());
        if (list.isEmpty()) byChunk.remove(ck);
        if (byChunk.isEmpty()) nodesByChunk.remove(wid);
    }

    public List<Location> getNodesInChunk(Chunk chunk) {
        UUID wid = chunk.getWorld().getUID();
        long ck = chunkKey(chunk.getX(), chunk.getZ());
        return nodesByChunk.getOrDefault(wid, Collections.emptyMap()).getOrDefault(ck, Collections.emptyList());
    }

    // ===== Helpers =====

    public int randomHits() {
        int min = plugin.getConfig().getInt("node.hits-min", 3);
        int max = plugin.getConfig().getInt("node.hits-max", 7);
        if (max < min) { int t = max; max = min; min = t; }
        return min + rnd.nextInt(Math.max(1, (max - min) + 1));
    }

    private boolean serverSolidEnabled() {
        return plugin.getConfig().getBoolean("visual.server-solid.enabled", false);
    }

    /**
     * АНТИ-ЦВЕТЫ:
     * Разрешаем в map только "полные" твердые блоки.
     * Цветы, факелы, таблички, вода, воздух и т.п. будут проигнорированы (вернём null).
     */
    private Material displayFor(Material ore) {
        String key = "visual.server-solid.map." + ore.name();
        String name = plugin.getConfig().getString(key);
        if (name == null || name.isBlank()) return null;

        Material m;
        try {
            m = Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            warnOnce("invalid:" + ore.name() + "->" + name,
                    "[BedrockOres] Invalid visual mapping: " + ore.name() + " -> " + name);
            return null;
        }

        // только блоки
        if (!m.isBlock()) {
            warnOnce("nonblock:" + ore.name() + "->" + m.name(),
                    "[BedrockOres] Blocked visual mapping (not a block): " + ore.name() + " -> " + m);
            return null;
        }

        // только "твердые" блоки
        if (!m.isSolid()) {
            warnOnce("nonsolid:" + ore.name() + "->" + m.name(),
                    "[BedrockOres] Blocked visual mapping (not solid): " + ore.name() + " -> " + m +
                    " (flowers/torches/etc are not allowed)");
            return null;
        }

        // и ещё: нельзя воздух
        if (m.isAir()) {
            warnOnce("air:" + ore.name() + "->" + m.name(),
                    "[BedrockOres] Blocked visual mapping (air): " + ore.name() + " -> " + m);
            return null;
        }

        return m;
    }

    private void warnOnce(String key, String msg) {
        if (warnedDisplayMaterials.add(key)) {
            plugin.getLogger().warning(msg);
        }
    }

    // Для совместимости с BedrockOresCommand
    public int applyServerVisualsInWorld(World world, boolean enable) {
        if (world == null) return 0;

        int changed = 0;
        UUID wid = world.getUID();

        for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
            String[] p = e.getKey().split(":");
            if (p.length != 4) continue;

            UUID w;
            try { w = UUID.fromString(p[0]); }
            catch (Exception ex) { continue; }

            if (!wid.equals(w)) continue;

            Location loc = locationFromKey(e.getKey());
            if (loc == null) continue;

            Material oreMaterial = e.getValue().oreMaterial;
            Material baseBlock = oreMaterial == Material.NETHERITE_SCRAP ? Material.ANCIENT_DEBRIS : oreMaterial;

            Material target = baseBlock;
            if (enable) {
                Material disp = displayFor(oreMaterial);
                if (disp != null) target = disp;
            }

            if (loc.getBlock().getType() != target) {
                loc.getBlock().setType(target, false);
                changed++;
            }
        }

        return changed;
    }

    public int applyServerVisualsForAllNodes(boolean forceApply) {
        if (!forceApply) return 0;

        boolean enabled = serverSolidEnabled();
        int changed = 0;

        for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
            Location loc = locationFromKey(e.getKey());
            if (loc == null) continue;
            if (loc.getWorld() == null) continue;

            Material oreMaterial = e.getValue().oreMaterial;
            Material baseBlock = oreMaterial == Material.NETHERITE_SCRAP ? Material.ANCIENT_DEBRIS : oreMaterial;

            Material target = baseBlock;
            if (enabled) {
                Material disp = displayFor(oreMaterial);
                if (disp != null) target = disp;
            }

            if (loc.getBlock().getType() != target) {
                loc.getBlock().setType(target, false);
                changed++;
            }
        }

        return changed;
    }
}
