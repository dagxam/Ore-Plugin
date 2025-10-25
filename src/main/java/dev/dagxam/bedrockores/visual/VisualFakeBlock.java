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
