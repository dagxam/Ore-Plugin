package dev.dagxam.bedrockores;

import dev.dagxam.bedrockores.cmd.BedrockOresCommand;
import dev.dagxam.bedrockores.gen.GenerationListener;
import dev.dagxam.bedrockores.node.NodeManager;
import dev.dagxam.bedrockores.node.OreListeners;
import dev.dagxam.bedrockores.visual.VisualFakeBlock;
import dev.dagxam.bedrockores.visual.VisualFakeBlockProtocol;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class BedrockOresPlugin extends JavaPlugin {

    private NodeManager nodeManager;
    private GenerationListener generationListener;

    // визуальные режимы (включается только один)
    private VisualFakeBlock visualFakeBlock;
    private VisualFakeBlockProtocol visualFakeBlockProto;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.nodeManager = new NodeManager(this);
        nodeManager.load();

        // Визуализация (выбери один режим в config.yml: fakeblock или fakeblock_proto)
        String visMode = getConfig().getString("visual.mode", "none").toLowerCase();
        switch (visMode) {
            case "fakeblock": {
                visualFakeBlock = new VisualFakeBlock(this, nodeManager);
                visualFakeBlock.start();
                getLogger().info("[Visual] fakeblock mode enabled");
                break;
            }
            case "fakeblock_proto": {
                if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
                    visualFakeBlockProto = new VisualFakeBlockProtocol(this, nodeManager);
                    visualFakeBlockProto.start();
                    getLogger().info("[Visual] fakeblock_proto mode enabled (ProtocolLib)");
                } else {
                    getLogger().warning("[Visual] fakeblock_proto selected but ProtocolLib is not installed! Falling back to none.");
                }
                break;
            }
            default:
                getLogger().info("[Visual] visual.mode = none");
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
            if (visualFakeBlockProto != null) visualFakeBlockProto.stop();
            if (visualFakeBlock != null) visualFakeBlock.stop();
            nodeManager.save();
        } catch (Exception e) {
            getLogger().severe("Failed to save nodes: " + e.getMessage());
        }
    }
}
