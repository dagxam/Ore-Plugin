package dev.dagxam.bedrockores;

import dev.dagxam.bedrockores.cmd.BedrockOresCommand;
import dev.dagxam.bedrockores.gen.GenerationListener;
import dev.dagxam.bedrockores.node.NodeManager;
import dev.dagxam.bedrockores.node.OreListeners;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class OrePlugin extends JavaPlugin {
    private NodeManager nodeManager;
    private GenerationListener generationListener;
    private BukkitTask asyncSaveTask;
    private BukkitTask respawnTickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!validateConfig(getConfig())) {
            getLogger().severe("Invalid configuration. Plugin disabled to prevent world corruption.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        nodeManager = new NodeManager(this);
        nodeManager.load();
        if (getConfig().getBoolean("visual.server-solid.enabled", false)) {
            getLogger().info("Server-solid visuals applied to nodes: " + nodeManager.applyServerVisualsForAllNodes(true));
        }
        generationListener = new GenerationListener(this, nodeManager);
        Bukkit.getPluginManager().registerEvents(generationListener, this);
        Bukkit.getPluginManager().registerEvents(new OreListeners(this, nodeManager), this);
        long seconds = Math.max(30L, getConfig().getLong("persistence.save-interval-seconds", 120L));
        asyncSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                Future<NodeManager.SaveSnapshot> future = Bukkit.getScheduler().callSyncMethod(this, nodeManager::createSnapshot);
                nodeManager.saveSnapshot(future.get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                getLogger().severe("Async save failed: " + e.getMessage());
            }
        }, 20L * seconds, 20L * seconds);
        respawnTickTask = Bukkit.getScheduler().runTaskTimer(this, nodeManager::tickRespawns, 20L, 20L * 30L);
        generationListener.startQueueIfEnabled();
        BedrockOresCommand command = new BedrockOresCommand(this, nodeManager, generationListener);
        if (getCommand("bedrockores") != null) {
            getCommand("bedrockores").setExecutor(command);
            getCommand("bedrockores").setTabCompleter(command);
        }
        getLogger().info("Ore Plugin enabled.");
    }

    private boolean validateConfig(FileConfiguration config) {
        boolean valid = true;
        int min = config.getInt("node.hits-min", 1);
        int max = config.getInt("node.hits-max", 1);
        if (min < 1 || max < min) valid = false;
        if (config.getLong("respawn.delay-seconds", 0) < 0) valid = false;
        return valid;
    }

    @Override
    public void onDisable() {
        try {
            if (asyncSaveTask != null) asyncSaveTask.cancel();
            if (respawnTickTask != null) respawnTickTask.cancel();
            if (generationListener != null) generationListener.stopQueue();
            if (nodeManager != null) nodeManager.save();
        } catch (Exception e) {
            getLogger().severe("Failed to save nodes: " + e.getMessage());
        }
    }

    public NodeManager getNodeManager() { return nodeManager; }
    public GenerationListener getGenerationListener() { return generationListener; }
}
