package dev.dagxam.bedrockores;

import dev.dagxam.bedrockores.cmd.BedrockOresCommand;
import dev.dagxam.bedrockores.gen.GenerationListener;
import dev.dagxam.bedrockores.node.NodeManager;
import dev.dagxam.bedrockores.node.OreListeners;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Главный класс плагина BedrockOres.
 * Изменение: периодическое сохранение вынесено в ASYNC-задачу,
 * чтобы YAML-сериализация не стопорила главный тред сервера.
 */
public class BedrockOresPlugin extends JavaPlugin {

    private NodeManager nodeManager;
    private GenerationListener generationListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.nodeManager = new NodeManager(this);
        nodeManager.load();

        // Визуалы «цельных блоков» (если включены)
        if (getConfig().getBoolean("visual.server-solid.enabled", false)) {
            int applied = nodeManager.applyServerVisualsForAllNodes(true);
            getLogger().info("Server-solid visuals applied to nodes: " + applied);
        }

        this.generationListener = new GenerationListener(this, nodeManager);
        Bukkit.getPluginManager().registerEvents(generationListener, this);
        Bukkit.getPluginManager().registerEvents(new OreListeners(this, nodeManager), this);

        // Периодическое сохранение: ПЕРЕВЕДЕНО В ASYNC
        // Можно настроить интервал в config.yml ключом persistence.save-interval-seconds (по умолчанию 180 сек)
        long saveSeconds = Math.max(30L, getConfig().getLong("persistence.save-interval-seconds", 180L));
        Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    try {
                        nodeManager.save(); // чисто файловая YAML-сериализация — безопасна вне главного треда
                    } catch (Exception e) {
                        getLogger().severe("Async save failed: " + e.getMessage());
                    }
                },
                20L * saveSeconds,
                20L * saveSeconds
        );

        // Тик респаунов (оставляем синхронно, т.к. трогает мир/чанки)
        Bukkit.getScheduler().runTaskTimer(this, nodeManager::tickRespawns, 20L, 20L * 30L);

        // Генерация: очередь, если включена конфигом
        generationListener.startQueueIfEnabled();

        BedrockOresCommand cmd = new BedrockOresCommand(this, nodeManager, generationListener);
        if (getCommand("bedrockores") != null) {
            getCommand("bedrockores").setExecutor(cmd);
            getCommand("bedrockores").setTabCompleter(cmd);
        }

        getLogger().info("BedrockOres enabled (async persistence, queue-aware generation).");
    }

    @Override
    public void onDisable() {
        try {
            if (generationListener != null) generationListener.stopQueue();
            // Финальный save — синхронно, чтобы гарантировать запись перед выключением
            nodeManager.save();
        } catch (Exception e) {
            getLogger().severe("Failed to save nodes: " + e.getMessage());
        }
    }

    public NodeManager getNodeManager() { return nodeManager; }
    public GenerationListener getGenerationListener() { return generationListener; }
}
