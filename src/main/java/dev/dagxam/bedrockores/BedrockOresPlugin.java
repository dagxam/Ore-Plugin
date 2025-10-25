package dev.dagxam.bedrockores;

import dev.dagxam.bedrockores.cmd.BedrockOresCommand;
import dev.dagxam.bedrockores.gen.GenerationListener;
import dev.dagxam.bedrockores.node.NodeManager;
import dev.dagxam.bedrockores.node.OreListeners;
import dev.dagxam.bedrockores.visual.VisualFakeBlock;
import dev.dagxam.bedrockores.visual.VisualOverlay;
import dev.dagxam.bedrockores.visual.VisualParticles;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class BedrockOresPlugin extends JavaPlugin {

    private NodeManager nodeManager;
    private GenerationListener generationListener;

    // визуальные режимы (используется только один, по visual.mode)
    private VisualOverlay visualOverlay;
    private VisualParticles visualParticles;
    private VisualFakeBlock visualFakeBlock;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.nodeManager = new NodeManager(this);
        nodeManager.load();

        // Визуализация
        String visMode = getConfig().getString("visual.mode", "none").toLowerCase();
        switch (visMode) {
            case "overlay": {
                visualOverlay = new VisualOverlay(this, nodeManager);
                nodeManager.setOverlay(visualOverlay);
                // после загрузки узлов — синхронизируем оверлеи (следующий тик)
                Bukkit.getScheduler().runTask(this, visualOverlay::syncAllFromNodes);
                break;
            }
            case "particles": {
                visualParticles = new VisualParticles(this, nodeManager);
                visualParticles.start();
                break;
            }
            case "fakeblock": {
                visualFakeBlock = new VisualFakeBlock(this, nodeManager);
                visualFakeBlock.start();
                break;
            }
            default:
                // none — ничего не включаем
        }

        this.generationListener = new GenerationListener(this, nodeManager);
        Bukkit.getPluginManager().registerEvents(generationListener, this);
        Bukkit.getPluginManager().registerEvents(new OreListeners(this, nodeManager), this);

        // Периодическое сохранение и тики респаунов
        Bukkit.getScheduler().runTaskTimer(this, nodeManager::save, 20L * 60L, 20L * 60L);
        Bukkit.getScheduler().runTaskTimer(this, nodeManager::tickRespawns, 20L, 20L * 30L);

        // Команда админа
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
            if (visualFakeBlock != null) visualFakeBlock.stop();
            if (visualParticles != null) visualParticles.stop();
            if (visualOverlay != null) visualOverlay.cleanup();
            nodeManager.save();
        } catch (Exception e) {
            getLogger().severe("Failed to save nodes: " + e.getMessage());
        }
    }
}
