package dev.dagxam.bedrockores;

import dev.dagxam.bedrockores.cmd.BedrockOresCommand;
import dev.dagxam.bedrockores.gen.GenerationListener;
import dev.dagxam.bedrockores.node.NodeManager;
import dev.dagxam.bedrockores.node.OreListeners;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class BedrockOresPlugin extends JavaPlugin {

    private NodeManager nodeManager;
    private GenerationListener generationListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.nodeManager = new NodeManager(this);
        nodeManager.load();

        this.generationListener = new GenerationListener(this, nodeManager);

        Bukkit.getPluginManager().registerEvents(generationListener, this);
        Bukkit.getPluginManager().registerEvents(new OreListeners(this, nodeManager), this);

        // Периодическое сохранение
        Bukkit.getScheduler().runTaskTimer(this, nodeManager::save, 20L * 60L, 20L * 60L);
        // Тики респаунов
        Bukkit.getScheduler().runTaskTimer(this, nodeManager::tickRespawns, 20L, 20L * 30L);

        // Команда
        BedrockOresCommand cmd = new BedrockOresCommand(this, nodeManager, generationListener);
        if (getCommand("bedrockores") != null) {
            getCommand("bedrockores").setExecutor(cmd);
            getCommand("bedrockores").setTabCompleter(cmd);
        }

        getLogger().info("BedrockOres enabled.");
    }

    @Override
    public void onDisable() {
        try {
            nodeManager.save();
        } catch (Exception e) {
            getLogger().severe("Failed to save nodes: " + e.getMessage());
        }
    }

    public NodeManager getNodeManager() {
        return nodeManager;
    }

    public GenerationListener getGenerationListener() {
        return generationListener;
    }
}
