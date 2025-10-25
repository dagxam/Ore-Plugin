import dev.dagxam.bedrockores.visual.VisualFakeBlock;
// ...

public class BedrockOresPlugin extends JavaPlugin {

    private NodeManager nodeManager;
    private GenerationListener generationListener;

    // визуальные режимы
    private VisualOverlay visualOverlay;      // если используешь overlay
    private VisualParticles visualParticles;  // если используешь particles
    private VisualFakeBlock visualFakeBlock;  // НОВОЕ

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.nodeManager = new NodeManager(this);
        nodeManager.load();

        String visMode = getConfig().getString("visual.mode", "none").toLowerCase();

        switch (visMode) {
            case "overlay": {
                visualOverlay = new VisualOverlay(this, nodeManager);
                nodeManager.setOverlay(visualOverlay);
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
                // none
        }

        this.generationListener = new GenerationListener(this, nodeManager);
        Bukkit.getPluginManager().registerEvents(generationListener, this);
        Bukkit.getPluginManager().registerEvents(new OreListeners(this, nodeManager), this);

        Bukkit.getScheduler().runTaskTimer(this, nodeManager::save, 20L * 60L, 20L * 60L);
        Bukkit.getScheduler().runTaskTimer(this, nodeManager::tickRespawns, 20L, 20L * 30L);

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
