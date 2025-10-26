package dev.dagxam.bedrockores.visual;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
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

import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class VisualFakeBlockProtocol {

    private final Plugin plugin;
    private final NodeManager nm;

    private final WrappedBlockData defaultFake;
    private final Map<Material, WrappedBlockData> perOreData = new HashMap<>();
    private final int periodTicks;
    private final int radiusChunks;

    private final Map<UUID, Set<String>> shown = new HashMap<>();
    private BukkitTask task;

    public VisualFakeBlockProtocol(Plugin plugin, NodeManager nm) {
        this.plugin = plugin;
        this.nm = nm;

        ConfigurationSection fb = plugin.getConfig().getConfigurationSection("visual.fakeblock");

        String defaultName = "LIGHT_BLUE_STAINED_GLASS";
        if (fb != null) defaultName = fb.getString("default", defaultName);
        Material defMat;
        try { defMat = Material.valueOf(defaultName); }
        catch (Exception e) { defMat = Material.LIGHT_BLUE_STAINED_GLASS; }
        this.defaultFake = WrappedBlockData.createData(defMat);

        if (fb != null) {
            ConfigurationSection map = fb.getConfigurationSection("map");
            if (map != null) {
                for (String oreName : map.getKeys(false)) {
                    try {
                        Material ore = Material.valueOf(oreName);
                        String glassName = map.getString(oreName, defaultName);
                        Material glassMat = Material.valueOf(glassName);
                        perOreData.put(ore, WrappedBlockData.createData(glassMat));
                    } catch (Exception ignored) {}
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
        // вернуть реал
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<String> set = shown.get(p.getUniqueId());
            if (set == null) continue;
            for (String key : new HashSet<>(set)) {
                Location loc = nm.toLocation(key);
                if (loc != null && loc.getWorld() == p.getWorld()) {
                    sendBlock(p, loc, loc.getBlock().getBlockData());
                }
            }
        }
        shown.clear();
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            int cx = p.getLocation().getBlockX() >> 4;
            int cz = p.getLocation().getBlockZ() >> 4;

            List<Location> nodes = nm.getNodesAroundChunks(w, cx, cz, radiusChunks);
            Set<String> newVisible = new HashSet<>();
            for (Location loc : nodes) newVisible.add(NodeManager.key(loc));

            Set<String> wasShown = shown.computeIfAbsent(p.getUniqueId(), id -> new HashSet<>());

            for (String key : newVisible) {
                if (!wasShown.contains(key)) {
                    Location loc = nm.toLocation(key);
                    if (loc == null || loc.getWorld() != w) continue;
                    sendBlock(p, loc, fakeDataFor(loc));
                    wasShown.add(key);
                }
            }

            Iterator<String> it = wasShown.iterator();
            while (it.hasNext()) {
                String key = it.next();
                if (!newVisible.contains(key) || !stillNodeInWorld(key, w)) {
                    Location loc = nm.toLocation(key);
                    if (loc != null && loc.getWorld() == w) {
                        sendBlock(p, loc, loc.getBlock().getBlockData());
                    }
                    it.remove();
                }
            }
        }
    }

    private WrappedBlockData fakeDataFor(Location loc) {
        NodeData nd = nm.getNode(loc);
        if (nd != null) {
            WrappedBlockData wbd = perOreData.get(nd.oreMaterial);
            if (wbd != null) return wbd;
        }
        return defaultFake;
    }

    private boolean stillNodeInWorld(String key, World w) {
        Location loc = nm.toLocation(key);
        if (loc == null || loc.getWorld() != w) return false;
        return nm.isNode(loc);
    }

    private void sendBlock(Player p, Location loc, BlockData data) {
        PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.BLOCK_CHANGE);
        packet.getBlockPositionModifier().write(0, new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        packet.getBlockData().write(0, WrappedBlockData.createData(data));
        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(p, packet);
        } catch (InvocationTargetException e) {
            plugin.getLogger().warning("Failed to send BLOCK_CHANGE: " + e.getMessage());
        }
    }

    private void sendBlock(Player p, Location loc, WrappedBlockData data) {
        PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.BLOCK_CHANGE);
        packet.getBlockPositionModifier().write(0, new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        packet.getBlockData().write(0, data);
        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(p, packet);
        } catch (InvocationTargetException e) {
            plugin.getLogger().warning("Failed to send BLOCK_CHANGE: " + e.getMessage());
        }
    }
}
