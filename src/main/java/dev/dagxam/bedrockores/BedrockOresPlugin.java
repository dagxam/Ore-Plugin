package dev.dagxam.bedrockores;

import dev.example.bedrockores.gen.GenerationListener;
import dev.example.bedrockores.node.NodeManager;
import dev.example.bedrockores.node.OreListeners;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class BedrockOresPlugin extends JavaPlugin {

    private NodeManager nodeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.nodeManager = new NodeManager(this);
        nodeManager.load();

        // Листенеры
        Bukkit.getPluginManager().registerEvents(new GenerationListener(this, nodeManager), this);
        Bukkit.getPluginManager().registerEvents(new OreListeners(this, nodeManager), this);

        // Периодическое сохранение
        Bukkit.getScheduler().runTaskTimer(this, nodeManager::save, 20L * 60L, 20L * 60L); // каждую минуту

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
}
