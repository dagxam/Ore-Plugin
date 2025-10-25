package dev.dagxam.bedrockores.visual;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class VisualFakeBlock {

    private final Plugin plugin;
    private final NodeManager nm;

    private final Material fakeMaterial;
    private final BlockData fakeData;
    private final int periodTicks;
    private final int radiusChunks;

    // какие узлы уже "перекрашены" для каждого игрока: playerUUID -> set(nodeKey)
    private final Map<UUID, Set<String>> shown = new HashMap<>();
    private BukkitTask task;

    public VisualFakeBlock(Plugin plugin, NodeManager nm) {
        this.plugin = plugin;
        this.nm = nm;

        String matName = plugin.getConfig().getString("visual.fakeblock.material", "LIGHT_BLUE_STAINED_GLASS");
        Material mat;
        try { mat = Material.valueOf(matName); } catch (Exception e) { mat = Material.LIGHT_BLUE_STAINED_GLASS; }
        this.fakeMaterial = mat;
        this.fakeData = fakeMaterial.createBlockData();

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

            // Новые видимые узлы — показать фейковый блок
            for (String key : newVisible) {
                if (!wasShown.contains(key)) {
                    Location loc = nm.toLocation(key);
                    if (loc == null || loc.getWorld() != w) continue;
                    // Покажем клиенту "другой" блок, реальный не меняется
                    p.sendBlockChange(loc, fakeData);
                    wasShown.add(key);
                }
            }

            // Узлы, которые больше не видимы/удалены — вернуть реальный блок
            Iterator<String> it = wasShown.iterator();
            while (it.hasNext()) {
                String key = it.next();
                if (!newVisible.contains(key) || !stillNodeInWorld(key, w)) {
                    Location loc = nm.toLocation(key);
                    if (loc != null && loc.getWorld() == w) {
                        p.sendBlockChange(loc, loc.getBlock().getBlockData());
                    }
                    it.remove();
                }
            }
        }
    }

    private boolean stillNodeInWorld(String key, World w) {
        Location loc = nm.toLocation(key);
        if (loc == null || loc.getWorld() != w) return false;
        return nm.isNode(loc);
    }
}
