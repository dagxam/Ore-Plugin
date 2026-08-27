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

public class BedrockOresPlugin extends JavaPlugin {
    private NodeManager nodeManager; private GenerationListener generationListener; private BukkitTask asyncSaveTask; private BukkitTask respawnTickTask;
    @Override public void onEnable(){
        saveDefaultConfig(); if(!validateConfig(getConfig())){getLogger().severe("Invalid configuration. Plugin disabled to prevent world corruption.");Bukkit.getPluginManager().disablePlugin(this);return;}
        nodeManager=new NodeManager(this);nodeManager.load();
        if(getConfig().getBoolean("visual.server-solid.enabled",false))getLogger().info("Server-solid visuals applied to nodes: "+nodeManager.applyServerVisualsForAllNodes(true));
        generationListener=new GenerationListener(this,nodeManager);Bukkit.getPluginManager().registerEvents(generationListener,this);Bukkit.getPluginManager().registerEvents(new OreListeners(this,nodeManager),this);
        long seconds=Math.max(30L,getConfig().getLong("persistence.save-interval-seconds",120L));
        asyncSaveTask=Bukkit.getScheduler().runTaskTimerAsynchronously(this,()->{try{Future<NodeManager.SaveSnapshot> f=Bukkit.getScheduler().callSyncMethod(this,nodeManager::createSnapshot);nodeManager.saveSnapshot(f.get(5,TimeUnit.SECONDS));}catch(Exception e){getLogger().severe("Async save failed: "+e.getMessage());}},20L*seconds,20L*seconds);
        respawnTickTask=Bukkit.getScheduler().runTaskTimer(this,nodeManager::tickRespawns,20L,20L*30L);
        generationListener.startQueueIfEnabled();
        BedrockOresCommand cmd=new BedrockOresCommand(this,nodeManager,generationListener);if(getCommand("bedrockores")!=null){getCommand("bedrockores").setExecutor(cmd);getCommand("bedrockores").setTabCompleter(cmd);}getLogger().info("BedrockOres enabled.");
    }
    private boolean validateConfig(FileConfiguration c){
        boolean ok=true;int min=c.getInt("node.hits-min",9),max=c.getInt("node.hits-max",20);if(min<1||max<min){getLogger().severe("node.hits-min/max are invalid");ok=false;}
        int csMin=c.getInt("generation.cluster.size-min",1),csMax=c.getInt("generation.cluster.size-max",3);if(csMin<1||csMax<csMin){getLogger().severe("generation.cluster.size-min/max are invalid");ok=false;}
        if(c.getInt("generation.cluster.min-spacing",1)<1||c.getInt("generation.cluster.vertical-spacing",0)<0){getLogger().severe("generation cluster spacing is invalid");ok=false;}
        if(c.getLong("respawn.delay-seconds",0)<0||c.getLong("persistence.save-interval-seconds",30)<30){getLogger().severe("persistence or respawn timing is invalid");ok=false;}
        if(c.getInt("generation.queue.chunks-per-tick",1)<1||c.getInt("generation.queue.positions-per-tick",32)<1||c.getInt("generation.queue.fill-attempts-per-tick",16)<1){getLogger().severe("generation queue budgets are invalid");ok=false;}
        return ok;
    }
    @Override public void onDisable(){try{if(asyncSaveTask!=null)asyncSaveTask.cancel();if(respawnTickTask!=null)respawnTickTask.cancel();if(generationListener!=null)generationListener.stopQueue();if(nodeManager!=null)nodeManager.save();}catch(Exception e){getLogger().severe("Failed to save nodes: "+e.getMessage());}}
    public NodeManager getNodeManager(){return nodeManager;} public GenerationListener getGenerationListener(){return generationListener;}
}
