package dev.dagxam.bedrockores.visual;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class VisualParticles {
    private final Plugin plugin;
    private final NodeManager nm;
    private final Random rnd = new Random();
    private BukkitTask task;

    private final Particle particle;
    private final int count;
    private final double offset;
    private final double extra;
    private final double perNodeChance;
    private final int periodTicks;
    private final int radiusChunks;
    private final int maxNodesPerTick;

    public VisualParticles(Plugin plugin, NodeManager nm) {
        this.plugin = plugin;
        this.nm = nm;

        String type = plugin.getConfig().getString("visual.particles.type", "END_ROD");
        Particle p;
        try { p = Particle.valueOf(type); } catch (Exception e) { p = Particle.END_ROD; }
        this.particle = p;

        this.count = Math.max(1, plugin.getConfig().getInt("visual.particles.count", 1));
        this.offset = plugin.getConfig().getDouble("visual.particles.offset", 0.12);
        this.extra = plugin.getConfig().getDouble("visual.particles.extra", 0.0);
        this.perNodeChance = plugin.getConfig().getDouble("visual.particles.per-node-chance", 0.12);
        this.periodTicks = Math.max(5, plugin.getConfig().getInt("visual.particles.period-ticks", 20));
        this.radiusChunks = Math.max(1, plugin.getConfig().getInt("visual.particles.radius-chunks", 2));
        this.maxNodesPerTick = Math.max(10, plugin.getConfig().getInt("visual.particles.max-nodes-per-tick", 150));
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, periodTicks, periodTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        int budget = maxNodesPerTick;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (budget <= 0) break;

            World w = p.getWorld();
            int cx = p.getLocation().getBlockX() >> 4;
            int cz = p.getLocation().getBlockZ() >> 4;

            List<Location> nodes = nm.getNodesAroundChunks(w, cx, cz, radiusChunks);
            if (nodes.isEmpty()) continue;

            Collections.shuffle(nodes, rnd);

            for (Location loc : nodes) {
                if (budget <= 0) break;
                if (!loc.getChunk().isLoaded()) continue;
                if (rnd.nextDouble() > perNodeChance) continue;

                w.spawnParticle(
                        particle,
                        loc.getBlockX() + 0.5,
                        loc.getBlockY() + 0.7,
                        loc.getBlockZ() + 0.5,
                        count, offset, offset, offset, extra
                );
                budget--;
            }
        }
    }
}
