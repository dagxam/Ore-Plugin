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
 * Менеджер состояния рудных узлов.
 *
 * Команды, permission и идентификаторы Bukkit остаются английскими.
 * Все настройки и технические сообщения плагина используют русские ключи.
 *
 * Индексы по чанкам не позволяют выполнять полный перебор всех узлов
 * при работе с одним чанком.
 */
public final class NodeManager {
    private final Plugin plugin;
    private final Random rnd = new Random();

    private final Map<String, NodeData> nodes = new HashMap<>();
    private final Map<UUID, Set<Long>> processedChunks = new HashMap<>();
    private final Map<String, RespawnData> respawns = new HashMap<>();

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

    public void addNode(Location loc, Material oreMaterial, int hits) {
        if (loc == null || loc.getWorld() == null || oreMaterial == null) return;

        int safeHits = Math.max(1, hits);
        String nodeKey = key(loc);
        NodeData previous = nodes.put(nodeKey, new NodeData(oreMaterial, safeHits, safeHits));
        if (previous == null) indexAdd(loc);

        Material toPlace = oreMaterial == Material.NETHERITE_SCRAP
                ? Material.ANCIENT_DEBRIS
                : oreMaterial;

        if (serverSolidEnabled()) {
            Material display = displayFor(oreMaterial);
            if (display != null) toPlace = display;
        }

        loc.getBlock().setType(toPlace, false);
    }

    public void removeNode(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        String nodeKey = key(loc);
        if (nodes.remove(nodeKey) != null) indexRemove(loc);
    }

    public void markChunkProcessed(World world, int cx, int cz) {
        if (world == null) return;
        processedChunks.computeIfAbsent(world.getUID(), ignored -> new HashSet<>())
                .add(chunkKey(cx, cz));
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

    public int removeNodesInChunk(Chunk chunk) {
        if (chunk == null) return 0;
        UUID worldId = chunk.getWorld().getUID();
        long ck = chunkKey(chunk.getX(), chunk.getZ());
        Map<Long, Set<String>> index = nodesByChunk.get(worldId);
        if (index == null) return 0;

        Set<String> keys = index.remove(ck);
        if (keys == null) return 0;

        int removed = 0;
        for (String k : new ArrayList<>(keys)) {
            if (nodes.remove(k) != null) removed++;
        }
        if (index.isEmpty()) nodesByChunk.remove(worldId);
        return removed;
    }

    public int removeRespawnsInChunk(Chunk chunk) {
        if (chunk == null) return 0;
        UUID worldId = chunk.getWorld().getUID();
        long ck = chunkKey(chunk.getX(), chunk.getZ());
        Map<Long, Set<String>> index = respawnsByChunk.get(worldId);
        if (index == null) return 0;

        Set<String> keys = index.remove(ck);
        if (keys == null) return 0;

        int removed = 0;
        for (String k : new ArrayList<>(keys)) {
            if (respawns.remove(k) != null) removed++;
        }
        if (index.isEmpty()) respawnsByChunk.remove(worldId);
        return removed;
    }

    public void scheduleRespawn(Location loc, Material oreType) {
        if (loc == null || loc.getWorld() == null || oreType == null) return;
        if (!plugin.getConfig().getBoolean("возрождение.включено", true)) return;

        long delaySeconds = Math.max(0L,
                plugin.getConfig().getLong("возрождение.задержка-секунд", 3600L));
        long dueAt = System.currentTimeMillis() + delaySeconds * 1000L;
        String k = key(loc);

        respawns.put(k, new RespawnData(oreType, dueAt));
        indexRespawnAdd(loc);

        if (plugin.getConfig().getBoolean("сохранение.сохранять-при-постановке-возрождения", false)) {
            save();
        }
    }

    public void tickRespawns() {
        if (respawns.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (Map.Entry<String, RespawnData> entry : new ArrayList<>(respawns.entrySet())) {
            if (!entry.getValue().isDue(now)) continue;

            Location loc = locationFromKey(entry.getKey());
            if (loc == null || loc.getWorld() == null) {
                removeRespawnKey(entry.getKey());
                continue;
            }

            World world = loc.getWorld();
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            if (!world.isChunkLoaded(cx, cz)) continue;

            addNode(loc, entry.getValue().oreMaterial, randomHits());
            removeRespawnKey(entry.getKey());
        }
    }

    public void processDueRespawnsInChunk(Chunk chunk) {
        if (chunk == null || respawns.isEmpty()) return;

        UUID worldId = chunk.getWorld().getUID();
        long ck = chunkKey(chunk.getX(), chunk.getZ());
        Map<Long, Set<String>> index = respawnsByChunk.get(worldId);
        if (index == null) return;

        Set<String> keys = index.get(ck);
        if (keys == null || keys.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (String k : new ArrayList<>(keys)) {
            RespawnData data = respawns.get(k);
            if (data == null) {
                keys.remove(k);
                continue;
            }
            if (!data.isDue(now)) continue;

            Location loc = locationFromKey(k);
            if (loc == null) {
                removeRespawnKey(k);
                continue;
            }

            addNode(loc, data.oreMaterial, randomHits());
            removeRespawnKey(k);
        }

        if (keys.isEmpty()) {
            index.remove(ck);
            if (index.isEmpty()) respawnsByChunk.remove(worldId);
        }
    }

    private void removeRespawnKey(String key) {
        RespawnData removed = respawns.remove(key);
        if (removed == null) return;

        String[] parts = splitKey(key);
        if (parts == null) return;
        try {
            UUID world = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[3]);
            removeFromIndex(respawnsByChunk, world, chunkKey(x >> 4, z >> 4), key);
        } catch (RuntimeException ignored) {
        }
    }

    private Location locationFromKey(String key) {
        String[] parts = splitKey(key);
        if (parts == null) return null;
        try {
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            if (world == null) return null;
            return new Location(world,
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String[] splitKey(String key) {
        if (key == null) return null;
        String[] parts = key.split(":", -1);
        return parts.length == 4 ? parts : null;
    }

    // =========================================================
    // Загрузка / сохранение
    // =========================================================

    public void load() {
        if (!dataFile.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        nodes.clear();
        respawns.clear();
        processedChunks.clear();
        nodesByChunk.clear();
        respawnsByChunk.clear();

        ConfigurationSection nodesSection = yml.getConfigurationSection("nodes");
        if (nodesSection != null) {
            for (String id : nodesSection.getKeys(false)) {
                ConfigurationSection section = nodesSection.getConfigurationSection(id);
                if (section == null) continue;
                try {
                    UUID world = UUID.fromString(Objects.requireNonNull(section.getString("world")));
                    int x = section.getInt("x");
                    int y = section.getInt("y");
                    int z = section.getInt("z");
                    Material type = Material.valueOf(Objects.requireNonNull(section.getString("type")));
                    int hits = section.getInt("hits");
                    int maxHits = section.getInt("maxHits", hits);
                    nodes.put(key(world, x, y, z), new NodeData(type, hits, maxHits));
                } catch (Exception ignored) {
                }
            }
        }

        // Старый формат nodes.yml сохраняется без изменений.
        ConfigurationSection processedSection = yml.getConfigurationSection("processedChunks");
        if (processedSection != null) {
            for (String worldName : processedSection.getKeys(false)) {
                try {
                    UUID world = UUID.fromString(worldName);
                    Set<Long> set = new HashSet<>();
                    for (String entry : processedSection.getStringList(worldName)) {
                        String[] parts = entry.split(":", -1);
                        if (parts.length != 2) continue;
                        set.add(chunkKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                    }
                    processedChunks.put(world, set);
                } catch (Exception ignored) {
                }
            }
        }

        ConfigurationSection respawnSection = yml.getConfigurationSection("respawns");
        if (respawnSection != null) {
            for (String id : respawnSection.getKeys(false)) {
                ConfigurationSection section = respawnSection.getConfigurationSection(id);
                if (section == null) continue;
                try {
                    UUID world = UUID.fromString(Objects.requireNonNull(section.getString("world")));
                    int x = section.getInt("x");
                    int y = section.getInt("y");
                    int z = section.getInt("z");
                    Material type = Material.valueOf(Objects.requireNonNull(section.getString("type")));
                    long dueAt = section.getLong("dueAt");
                    respawns.put(key(world, x, y, z), new RespawnData(type, dueAt));
                } catch (Exception ignored) {
                }
            }
        }

        rebuildIndex();
        plugin.getLogger().info("Загружено рудных узлов: " + nodes.size()
                + "; ожидает возрождения: " + respawns.size()
                + "; миров с обработанными чанками: " + processedChunks.size());
    }

    public record NodeEntry(UUID world, int x, int y, int z, Material type, int hits, int maxHits) {}
    public record RespawnEntry(UUID world, int x, int y, int z, Material type, long dueAtMillis) {}
    public record SaveSnapshot(List<NodeEntry> nodes,
                               Map<UUID, List<String>> processedChunks,
                               List<RespawnEntry> respawns) {}

    public SaveSnapshot createSnapshot() {
        List<NodeEntry> nodeList = new ArrayList<>(nodes.size());
        for (Map.Entry<String, NodeData> entry : nodes.entrySet()) {
            String[] p = splitKey(entry.getKey());
            if (p == null) continue;
            try {
                NodeData data = entry.getValue();
                nodeList.add(new NodeEntry(
                        UUID.fromString(p[0]),
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]),
                        data.oreMaterial,
                        data.hitsRemaining,
                        data.maxHits
                ));
            } catch (RuntimeException ignored) {
            }
        }

        Map<UUID, List<String>> processed = new HashMap<>();
        for (Map.Entry<UUID, Set<Long>> entry : processedChunks.entrySet()) {
            List<String> list = new ArrayList<>(entry.getValue().size());
            for (Long value : entry.getValue()) {
                int cx = (int) (value >> 32);
                int cz = (int) value.longValue();
                list.add(cx + ":" + cz);
            }
            processed.put(entry.getKey(), list);
        }

        List<RespawnEntry> respawnList = new ArrayList<>(respawns.size());
        for (Map.Entry<String, RespawnData> entry : respawns.entrySet()) {
            String[] p = splitKey(entry.getKey());
            if (p == null) continue;
            try {
                RespawnData data = entry.getValue();
                respawnList.add(new RespawnEntry(
                        UUID.fromString(p[0]),
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]),
                        data.oreMaterial,
                        data.dueAtMillis
                ));
            } catch (RuntimeException ignored) {
            }
        }

        return new SaveSnapshot(nodeList, processed, respawnList);
    }

    public void saveSnapshot(SaveSnapshot snapshot) {
        YamlConfiguration yml = new YamlConfiguration();

        int nodeIndex = 0;
        for (NodeEntry node : snapshot.nodes()) {
            String path = "nodes.n" + nodeIndex++;
            yml.set(path + ".world", node.world().toString());
            yml.set(path + ".x", node.x());
            yml.set(path + ".y", node.y());
            yml.set(path + ".z", node.z());
            yml.set(path + ".type", node.type().name());
            yml.set(path + ".hits", node.hits());
            yml.set(path + ".maxHits", node.maxHits());
        }

        for (Map.Entry<UUID, List<String>> entry : snapshot.processedChunks().entrySet()) {
            yml.set("processedChunks." + entry.getKey(), entry.getValue());
        }

        int respawnIndex = 0;
        for (RespawnEntry respawn : snapshot.respawns()) {
            String path = "respawns.r" + respawnIndex++;
            yml.set(path + ".world", respawn.world().toString());
            yml.set(path + ".x", respawn.x());
            yml.set(path + ".y", respawn.y());
            yml.set(path + ".z", respawn.z());
            yml.set(path + ".type", respawn.type().name());
            yml.set(path + ".dueAt", respawn.dueAtMillis());
        }

        try {
            File parent = dataFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку данных плагина: " + parent);
            }
            yml.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Не удалось сохранить nodes.yml: " + ex.getMessage());
        }
    }

    public void save() {
        saveSnapshot(createSnapshot());
    }

    // =========================================================
    // Индексы
    // =========================================================

    private void indexAdd(Location loc) {
        UUID world = loc.getWorld().getUID();
        long chunk = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        nodesByChunk.computeIfAbsent(world, ignored -> new HashMap<>())
                .computeIfAbsent(chunk, ignored -> new HashSet<>())
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
        UUID world = loc.getWorld().getUID();
        long chunk = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        respawnsByChunk.computeIfAbsent(world, ignored -> new HashMap<>())
                .computeIfAbsent(chunk, ignored -> new HashSet<>())
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
                nodesByChunk.computeIfAbsent(world, ignored -> new HashMap<>())
                        .computeIfAbsent(chunkKey(x >> 4, z >> 4), ignored -> new HashSet<>())
                        .add(k);
            } catch (RuntimeException ignored) {
            }
        }

        for (String k : respawns.keySet()) {
            String[] p = splitKey(k);
            if (p == null) continue;
            try {
                UUID world = UUID.fromString(p[0]);
                int x = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[3]);
                respawnsByChunk.computeIfAbsent(world, ignored -> new HashMap<>())
                        .computeIfAbsent(chunkKey(x >> 4, z >> 4), ignored -> new HashSet<>())
                        .add(k);
            } catch (RuntimeException ignored) {
            }
        }
    }

    // =========================================================
    // Игровые параметры и визуализация
    // =========================================================

    /** Используется генератором для определения прочности нового узла. */
    public int randomHits() {
        int min = Math.max(1, plugin.getConfig().getInt("узел.ударов-минимум", 9));
        int max = Math.max(min, plugin.getConfig().getInt("узел.ударов-максимум", 20));
        return min + rnd.nextInt(max - min + 1);
    }

    private boolean serverSolidEnabled() {
        return plugin.getConfig().getBoolean("визуал.серверный-блок.включено", false);
    }

    private Material displayFor(Material oreMaterial) {
        String path = "визуал.серверный-блок.соответствия." + oreMaterial.name();
        String raw = plugin.getConfig().getString(path);
        if (raw == null || raw.isBlank()) return null;

        try {
            Material material = Material.valueOf(raw.toUpperCase(Locale.ROOT));
            if (!material.isBlock()) {
                warnOnce("Визуальный материал не является блоком: " + raw);
                return null;
            }
            return material;
        } catch (IllegalArgumentException ex) {
            warnOnce("Неизвестный визуальный материал: " + raw + " для " + oreMaterial);
            return null;
        }
    }

    private void warnOnce(String message) {
        if (warnedDisplayMaterials.add(message)) plugin.getLogger().warning(message);
    }

    public int applyServerVisualsInWorld(World world, boolean loadedChunksOnly) {
        if (world == null || !serverSolidEnabled()) return 0;
        Map<Long, Set<String>> byChunk = nodesByChunk.get(world.getUID());
        if (byChunk == null) return 0;

        int count = 0;
        for (Set<String> keys : new ArrayList<>(byChunk.values())) {
            for (String k : new ArrayList<>(keys)) {
                NodeData data = nodes.get(k);
                if (data == null) continue;
                String[] p = splitKey(k);
                if (p == null) continue;
                try {
                    int x = Integer.parseInt(p[1]);
                    int y = Integer.parseInt(p[2]);
                    int z = Integer.parseInt(p[3]);
                    if (loadedChunksOnly && !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Material display = displayFor(data.oreMaterial);
                    if (display == null) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != display) block.setType(display, false);
                    count++;
                } catch (RuntimeException ignored) {
                }
            }
        }
        return count;
    }

    public int applyServerVisualsForAllNodes(boolean loadedChunksOnly) {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += applyServerVisualsInWorld(world, loadedChunksOnly);
        }
        return total;
    }
}
