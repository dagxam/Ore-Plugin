package dev.dagxam.bedrockores;

import dev.dagxam.bedrockores.cmd.BedrockOresCommand;
import dev.dagxam.bedrockores.gen.GenerationListener;
import dev.dagxam.bedrockores.node.NodeManager;
import dev.dagxam.bedrockores.node.OreListeners;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Главный класс плагина BedrockOres.
 *
 * ВАЖНО ПРО ПЕРИОДИЧЕСКОЕ СОХРАНЕНИЕ:
 * - YAML-сериализация делается в async (чтобы не лагать основной тред).
 * - Но «снимок» данных берётся синхронно (в main thread), иначе возможны
 *   ConcurrentModificationException и/или частично неконсистентные данные.
 */
public class BedrockOresPlugin extends JavaPlugin {

    private NodeManager nodeManager;
    private GenerationListener generationListener;

    private BukkitTask asyncSaveTask;
    private BukkitTask respawnTickTask;

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

        // Периодическое сохранение: snapshot синхронно, запись async
        long saveSeconds = Math.max(30L, getConfig().getLong("persistence.save-interval-seconds", 180L));
        asyncSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    try {
                        Future<NodeManager.SaveSnapshot> f = Bukkit.getScheduler()
                                .callSyncMethod(this, nodeManager::createSnapshot);

                        // Снимок обычно делается быстро; чтобы не зависнуть на shutdown/лаг-спайках — ограничиваем ожидание.
                        NodeManager.SaveSnapshot snap = f.get(2, TimeUnit.SECONDS);
                        nodeManager.saveSnapshot(snap); // чисто файловая запись/сериализация — можно async
                    } catch (Exception e) {
                        getLogger().severe("Async save failed: " + e.getMessage());
                    }
                },
                20L * saveSeconds,
                20L * saveSeconds
        );

        // Тик респаунов (синхронно, т.к. трогает мир/чанки)
        respawnTickTask = Bukkit.getScheduler().runTaskTimer(this, nodeManager::tickRespawns, 20L, 20L * 30L);

        // Генерация: очередь, если включена конфигом
        generationListener.startQueueIfEnabled();

        BedrockOresCommand cmd = new BedrockOresCommand(this, nodeManager, generationListener);
        if (getCommand("bedrockores") != null) {
            getCommand("bedrockores").setExecutor(cmd);
            getCommand("bedrockores").setTabCompleter(cmd);
        }

        getLogger().info("BedrockOres enabled (snapshot-based async persistence, queue-aware generation).");
    }

    @Override
    public void onDisable() {
        try {
            if (asyncSaveTask != null) asyncSaveTask.cancel();
            if (respawnTickTask != null) respawnTickTask.cancel();
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
