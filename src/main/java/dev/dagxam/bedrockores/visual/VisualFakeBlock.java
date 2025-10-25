package dev.dagxam.bedrockores.visual;

import dev.dagxam.bedrockores.node.NodeData;
import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class VisualFakeBlock {

    private final Plugin plugin;
    private final NodeManager nm;

    private final BlockData defaultFakeData;
    private final Map<Material, BlockData> perOreData = new HashMap<>();
    private final int periodTicks;
    private final int radiusChunks;

    // какие узлы уже "перекрашены" для каждого игрока: playerUUID -> set(nodeKey)
    private final Map<UUID, Set<String>> shown = new HashMap<>();
    private BukkitTask task;

    public VisualFakeBlock(Plugin plugin, NodeManager nm) {
        this.plugin = plugin;
        this.nm = nm;

        ConfigurationSection fb = plugin.getConfig().getConfigurationSection("visual.fakeblock");

        // default материал
        String defaultName = "LIGHT_BLUE_STAINED_GLASS";
        if (fb != null) defaultName = fb.getString("default", defaultName);
        Material defMat;
        try { defMat = Material.valueOf(defaultName); } catch (Exception e) {
            plugin.getLogger().warning("Bad visual.fakeblock.default: " + defaultName + " — fallback LIGHT_BLUE_STAINED_GLASS");
            defMat = Material.LIGHT_BLUE_STAINED_GLASS;
        }
        this.defaultFakeData = defMat.createBlockData();

        // карта руда -> стекло
        if (fb != null) {
            ConfigurationSection map = fb.getConfigurationSection("map");
            if (map != null) {
                for (String oreName : map.getKeys(false)) {
                    try {
                        Material ore = Material.valueOf(oreName);
                        String glassName = map.getString(oreName, defaultName);
                        Material glassMat = Material.valueOf(glassName);
                        perOreData.put(ore, glassMat.createBlockData());
                    } catch (Exception ex) {
                        plugin.getLogger().warning("visual.fakeblock.map: skip invalid entry " + oreName + " -> " + map.getString(oreName));
                    }
                }
            }
        }

        this.periodTicks = Math.max(5, plugin.getConfig().getInt("visual.fakeblock.period-ticks", 20));
        this.radiusChunks = Math.max(1, plugin.getConfig().getInt("visual.fakeblock.radius-chunks", 3));
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, periodTicks, periodTicks);
    }

    public void stop() {
        // Вернём всем игрокам реальные блоки вместо фейковых
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<String> set = shown.get(p.getUniqueId());
            if (set == null) continue;
            for (String key : new HashSet<>(set)) {
                Location loc = nm.toLocation(key);
                if (loc != null && loc.getWorld() == p.getWorld()) {
                    p.sendBlockChange(loc, loc.getBlock().getBlockData());
                }
            }
        }
        shown.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            int cx = p.getLocation().getBlockX() >> 4;
            int cz = p.getLocation().getBlockZ() >> 4;

            // узлы рядом, по чанкам
            List<Location> nodes = nm.getNodesAroundChunks(w, cx, cz, radiusChunks);
            Set<String> newVisible = new HashSet<>();
            for (Location loc : nodes) {
                newVisible.add(NodeManager.key(loc));
            }

            Set<String> wasShown = shown.computeIfAbsent(p.getUniqueId(), id -> new HashSet<>());

            // Новые видимые узлы — показать фейковый блок (по карте цветов)
            for (String key : newVisible) {
                if (!wasShown.contains(key)) {
                    Location loc = nm.toLocation(key);
                    if (loc == null || 
