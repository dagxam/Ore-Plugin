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

    // Активные узлы: key(worldUUID:x:y:z) -> NodeData (исходная руда + прогресс)
    private final Map<String, NodeData> nodes = new HashMap<>();
    // Обработанные чанки (для одноразовой генерации)
    private final Map<UUID, Set<Long>> processedChunks = new HashMap<>();
    // Очередь респаунов
    private final Map<String, RespawnData> respawns = new HashMap<>();
    // Индекс узлов по чанкам (для быстрых выборок)
    private final Map<UUID, Map<Long, List<Location>>> nodesByChunk = new HashMap<>();

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

    // Добавить узел на блок (физически ставим отображаемый блок согласно режиму)
    public void addNode(Location loc, Material oreMaterial, int hits) {
        int max = hits;
        NodeData nd = new NodeData(oreMaterial, hits, max);
        nodes.put(key(loc), nd);

        Material toPlace = oreMaterial;
        if (serverSolidEnabled()) {
            Material disp = displayFor(oreMaterial);
            if (disp != null) toPlace = disp;
        }
        loc.getBlock().setType(toPlace, false);

        indexAdd(loc);
    }

    // Удалить узел (блок на месте узла не меняем — логика замены отдельно)
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

    public void clearProcessedFlags(World world) {
        processedChunks.remove(world.getUID());
    }

    public void clearAllProcessedFlags() {
        processedChunks.clear();
    }

    // Удалить все узлы в чанке (для перегенерации)
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

    // Удалить все отложенные респавны в чанке
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

    // Запланировать респаун узла через delay-seconds
    public void scheduleRespawn(Location loc, Material oreType) {
        if (!plugin.getConfig().getBoolean("respawn.enabled", true)) return;
        long delaySec = plugin.getConfig().getLong("respawn.delay-seconds", 3600L);
        long due = System.currentTimeMillis() + delaySec * 1000L;
        respawns.put(key(loc), new RespawnData(oreType, due));
        save();
    }

    // Периодический тик респаунов: возрождаем только если чанк загружен
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
        if (!done.isEmpty()) save();
    }

    // При загрузке чанка — если есть просроченные респавны в нём, возрождаем
    public void processDueRespawnsInChunk(Chunk chunk) {
        if (respawns.isEmpty()) return;
        long now = System.currentTimeMillis();
        UUID wid = chunk.getWorld().getUID();
        int cx = chunk.getX();
        int cz = chunk.getZ();

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
        if (!done.isEmpty()) save();
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

    // Загрузка узлов/флагов/респавнов из файла
    public void load() {
        if (!dataFile.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);

        // Узлы
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
                    Material mat = Material.valueOf(s.getString("type"));
                    int hits = s.getInt("hits");
                    int maxHits = s.getInt("maxHits");

                    World w = Bukkit.getWorld(worldId);
                    if (w == null) continue;

                    Location loc = new Location(w, x, y, z);
                    nodes.put(key(loc), new NodeData(mat, hits, maxHits));

                    // Восстановим отображение блока (цельный вид, если включен режим)
                    Material toPlace = mat;
                    if (serverSolidEnabled()) {
                        Material disp = displayFor(mat);
                        if (disp != null) toPlace = disp;
                    }
                    if (loc.getBlock().getType() != toPlace) {
                        loc.getBlock().setType(toPlace, false);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Bad node entry: " + id + " -> " + ex.getMessage());
                }
            }
        }

        // Флаги обработанных чанков
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
                } catch (Exception ex) {
                    plugin.getLogger().warning("Bad processedChunks entry: " + worldKey);
                }
            }
        }

        // Очередь респавнов
        ConfigurationSection respSec = yml.getConfigurationSection("respawns");
        if (respSec != null) {
            for (String id : respSec.getKeys(false)) {
                ConfigurationSection s = respSec.getConfigurationSection(id);
                if (s == null) continue;
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
                } catch (Exception ex) {
                    plugin.getLogger().warning("Bad respawn entry: " + id + " -> " + ex.getMessage());
                }
            }
        }

        rebuildIndex();
        plugin.getLogger().info("Loaded nodes=" + nodes.size() + ", respawns=" + respawns.size() + ", processed worlds=" + processedChunks.size());
    }

    // Сохранение узлов/флагов/респавнов
    public void save() {
        YamlConfiguration yml = new YamlConfiguration();

        int i = 0;
        for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
            String[] parts = e.getKey().split(":");
            UUID world = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);

            NodeData nd = e.getValue();
            String path = "nodes.n" + (i++);
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
            String[] parts = e.getKey().split(":");
            UUID world = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);

            RespawnData rd = e.getValue();
            String path = "respawns.r" + (r++);
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

    // ===== Индексация по чанкам =====

    private void indexAdd(Location loc) {
        Map<Long, List<Location>> map = nodesByChunk.computeIfAbsent(loc.getWorld().getUID(), k -> new HashMap<>());
        long ck = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        List<Location> list = map.computeIfAbsent(ck, k -> new ArrayList<>());
        list.add(loc.clone());
    }

    private void indexRemove(Location loc) {
        Map<Long, List<Location>> map = nodesByChunk.get(loc.getWorld().getUID());
        if (map == null) return;
        long ck = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        List<Location> list = map.get(ck);
        if (list == null) return;
        list.removeIf(l -> l.getBlockX() == loc.getBlockX() && l.getBlockY() == loc.getBlockY() && l.getBlockZ() == loc.getBlockZ());
        if (list.isEmpty()) map.remove(ck);
    }

    private void rebuildIndex() {
        nodesByChunk.clear();
        for (String k : nodes.keySet()) {
            Location loc = toLocation(k);
            if (loc != null && loc.getWorld() != null) indexAdd(loc);
        }
    }

    public Set<String> nodeKeysSnapshot() {
        return new HashSet<>(nodes.keySet());
    }

    public Location toLocation(String k) {
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

    public List<Location> getNodesAroundChunks(World w, int cx, int cz, int radius) {
        Map<Long, List<Location>> map = nodesByChunk.get(w.getUID());
        List<Location> out = new ArrayList<>();
        if (map == null) return out;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long ck = chunkKey(cx + dx, cz + dz);
                List<Location> list = map.get(ck);
                if (list != null) out.addAll(list);
            }
        }
        return out;
    }

    // ===== Режим «цельные» серверные блоки =====

    private boolean serverSolidEnabled() {
        return plugin.getConfig().getBoolean("visual.server-solid.enabled", false);
    }

    private Material displayFor(Material ore) {
        // 1) Переопределение из конфига
        try {
            ConfigurationSection map = plugin.getConfig().getConfigurationSection("visual.server-solid.map");
            if (map != null) {
                String name = map.getString(ore.name());
                if (name != null && !name.isEmpty()) {
                    try {
                        return Material.valueOf(name);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // 2) Дефолтная карта "руда -> цельный блок"
        switch (ore) {
            case DEEPSLATE_DIAMOND_ORE: return Material.DIAMOND_BLOCK;
            case DEEPSLATE_EMERALD_ORE: return Material.EMERALD_BLOCK;
            case DEEPSLATE_REDSTONE_ORE: return Material.REDSTONE_BLOCK; // подаёт сигнал
            case DEEPSLATE_LAPIS_ORE:   return Material.LAPIS_BLOCK;
            case DEEPSLATE_COAL_ORE:    return Material.COAL_BLOCK;
            case DEEPSLATE_COPPER_ORE:  return Material.COPPER_BLOCK;
            case DEEPSLATE_IRON_ORE:    return Material.IRON_BLOCK;
            case DEEPSLATE_GOLD_ORE:    return Material.GOLD_BLOCK;
            default: return ore;
        }
    }

    // ===== Применение «цельного» вида к уже существующим узлам =====

    public int applyServerVisualsInWorld(World world, boolean onlyLoadedChunks) {
        if (!serverSolidEnabled()) return 0;
        int changed = 0;
        UUID wid = world.getUID();

        for (String k : nodes.keySet()) {
            Location loc = toLocation(k);
            if (loc == null) continue;
            if (!loc.getWorld().getUID().equals(wid)) continue;

            if (onlyLoadedChunks && !loc.getChunk().isLoaded()) continue;

            NodeData nd = nodes.get(k);
            if (nd == null) continue;

            Material toPlace = displayFor(nd.oreMaterial);
            if (toPlace == null) toPlace = nd.oreMaterial;

            if (loc.getBlock().getType() != toPlace) {
                loc.getBlock().setType(toPlace, false);
                changed++;
            }
        }
        return changed;
    }

    public int applyServerVisualsForAllNodes(boolean onlyLoadedChunks) {
        if (!serverSolidEnabled()) return 0;
        int total = 0;
        for (World w : plugin.getServer().getWorlds()) {
            total += applyServerVisualsInWorld(w, onlyLoadedChunks);
        }
        return total;
    }
}
