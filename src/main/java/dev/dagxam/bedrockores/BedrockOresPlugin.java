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
 * Главный класс BedrockOres.
 *
 * Технические сообщения плагина выводятся на русском языке.
 * Названия команд, API-ключи Bukkit и идентификаторы материалов
 * остаются в стандартном английском виде для совместимости.
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

        // Применяем серверный внешний вид узлов, если он включён.
        if (getConfig().getBoolean("визуал.серверный-блок.включено", false)) {
            int applied = nodeManager.applyServerVisualsForAllNodes(true);
            getLogger().info("Серверный внешний вид применён к узлам: " + applied);
        }

        this.generationListener = new GenerationListener(this, nodeManager);
        Bukkit.getPluginManager().registerEvents(generationListener, this);
        Bukkit.getPluginManager().registerEvents(new OreListeners(this, nodeManager), this);

        // Периодическое сохранение: создание снимка синхронно, запись — асинхронно.
        long saveSeconds = Math.max(
                30L,
                getConfig().getLong("сохранение.интервал-секунд", 120L)
        );

        asyncSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    try {
                        Future<NodeManager.SaveSnapshot> future =
                                Bukkit.getScheduler().callSyncMethod(this, nodeManager::createSnapshot);

                        NodeManager.SaveSnapshot snapshot = future.get(2, TimeUnit.SECONDS);
                        nodeManager.saveSnapshot(snapshot);
                    } catch (Exception e) {
                        getLogger().severe("Не удалось сохранить данные рудных узлов: " + e.getMessage());
                    }
                },
                20L * saveSeconds,
                20L * saveSeconds
        );

        // Периодическая обработка респавнов.
        respawnTickTask = Bukkit.getScheduler().runTaskTimer(
                this,
                nodeManager::tickRespawns,
                20L,
                20L * 30L
        );

        // Запускаем очередь генерации.
        generationListener.startQueueIfEnabled();

        // Команда намеренно остаётся /bedrockores для совместимости.
        BedrockOresCommand command = new BedrockOresCommand(
                this,
                nodeManager,
                generationListener
        );

        if (getCommand("bedrockores") != null) {
            getCommand("bedrockores").setExecutor(command);
            getCommand("bedrockores").setTabCompleter(command);
        }

        getLogger().info("BedrockOres успешно запущен.");
    }

    @Override
    public void onDisable() {
        try {
            if (asyncSaveTask != null) {
                asyncSaveTask.cancel();
            }

            if (respawnTickTask != null) {
                respawnTickTask.cancel();
            }

            if (generationListener != null) {
                generationListener.stopQueue();
            }

            if (nodeManager != null) {
                nodeManager.save();
            }

            getLogger().info("BedrockOres остановлен. Данные сохранены.");
        } catch (Exception e) {
            getLogger().severe("Не удалось сохранить данные при остановке плагина: " + e.getMessage());
        }
    }

    public NodeManager getNodeManager() {
        return nodeManager;
    }

    public GenerationListener getGenerationListener() {
        return generationListener;
    }
}
