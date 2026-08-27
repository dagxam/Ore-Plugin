package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** Adds rare rich ore veins after normal Minecraft terrain/ore generation. */
public class GenerationListener implements Listener {
    private final Plugin plugin;
    private final NodeManager nodeManager;
    private final Random random = new Random();
    private final Map<Material, Integer> overworldWeights = new LinkedHashMap<>();
    private final Map<Material, Integer> netherWeights = new LinkedHashMap<>();
    private final Map<Material, Integer> endWeights = new LinkedHashMap<>();
    private final ArrayDeque<ChunkJob> queue = new ArrayDeque<>();
    private final Set<ChunkJob> queued = new HashSet<>();
    private BukkitTask queueTask;
    private boolean queueEnabled;
    private int chunksPerTick;
    private int positionsPerTick;
    private int remainingPositions;
    private record ChunkJob(UUID worldId,int x,int z) {}
    public GenerationListener(Plugin plugin,NodeManager nodeManager){this.plugin=plugin;this.nodeManager=nodeManager;reloadSettings();}
    public void reloadSettings(){reloadWeights();ConfigurationSection q=plugin.getConfig().getConfigurationSection("generation.queue");queueEnabled=q==null||q.getBoolean("enabled",true);chunksPerTick=Math.max(1,q==null?2:q.getInt("chunks-per-tick",2));positionsPerTick=Math.max(16,q==null?128:q.getInt("positions-per-tick",128));stopQueue();startQueueIfEnabled();}
    public void reloadWeights(){loadWeights("ore-weights",overworldWeights);loadWeights("ore-weights-nether",netherWeights);loadWeights("ore-weights-end",endWeights);if(overworldWeights.isEmpty()){overworldWeights.put(Material.DEEPSLATE_IRON_ORE,6);overworldWeights.put(Material.DEEPSLATE_GOLD_ORE,3);overworldWeights.put(Material.DEEPSLATE_DIAMOND_ORE,1);}if(netherWeights.isEmpty())netherWeights.put(Material.ANCIENT_DEBRIS,1);if(endWeights.isEmpty())endWeights.put(Material.DEEPSLATE_DIAMOND_ORE,1);}
    private void loadWeights(String path,Map<Material,Integer> target){target.clear();ConfigurationSection s=plugin.getConfig().getConfigurationSection(path);if(s==null)return;for(String n:s.getKeys(false))try{Material m=Material.valueOf(n);int w=s.getInt(n);if(w>0&&allowed(m))target.put(m,w);}catch(IllegalArgumentException e){plugin.getLogger().warning("Invalid ore material in "+path+": "+n);}}
    private boolean allowed(Material m){return m==Material.ANCIENT_DEBRIS||m.name().endsWith("_ORE");}
    public void startQueueIfEnabled(){if(queueEnabled&&queueTask==null)queueTask=Bukkit.getScheduler().runTaskTimer(plugin,this::drainQueue,1L,1L);}
    public void stopQueue(){if(queueTask!=null)queueTask.cancel();queueTask=null;queue.clear();queued.clear();}
    @EventHandler public void onChunkLoad(ChunkLoadEvent e){Chunk c=e.getChunk();if(!enabled(c.getWorld()))return;if(nodeManager.isChunkProcessed(c.getWorld(),c.getX(),c.getZ())){nodeManager.processDueRespawnsInChunk(c);return;}if(queueEnabled)offer(c);else generateNow(c);}
    private void offer(Chunk c){ChunkJob j=new ChunkJob(c.getWorld().getUID(),c.getX(),c.getZ());if(queued.add(j))queue.add(j);}
    private void drainQueue(){remainingPositions=positionsPerTick;int chunks=chunksPerTick;while(chunks-->0&&remainingPositions>0&&!queue.isEmpty()){ChunkJob j=queue.poll();queued.remove(j);World w=Bukkit.getWorld(j.worldId());if(w==null||!w.isChunkLoaded(j.x(),j.z())||!enabled(w))continue;Chunk c=w.getChunkAt(j.x(),j.z());if(!nodeManager.isChunkProcessed(w,j.x(),j.z()))generateNow(c);nodeManager.processDueRespawnsInChunk(c);}}
    private void generateNow(Chunk c){generateInChunk(c);nodeManager.markChunkProcessed(c.getWorld(),c.getX(),c.getZ());}
    private boolean enabled(World w){List<String> worlds=plugin.getConfig().getStringList("enabled-worlds");return worlds.isEmpty()||worlds.contains(w.getName());}
    public void generateInChunk(Chunk c){World w=c.getWorld();Map<Material,Integer> weights=weightsFor(w);if(weights.isEmpty())return;int minY=w.getMinHeight(),maxY=w.getMaxHeight()-1;if(plugin.getConfig().isInt("generation.y-min"))minY=Math.max(minY,plugin.getConfig().getInt("generation.y-min"));if(plugin.getConfig().isInt("generation.y-max"))maxY=Math.min(maxY,plugin.getConfig().getInt("generation.y-max"));if(maxY<minY)return;int veins=Math.max(0,plugin.getConfig().getInt("generation.veins-per-chunk",2));int veinMin=Math.max(1,plugin.getConfig().getInt("generation.vein-size-min",3));int veinMax=Math.max(veinMin,plugin.getConfig().getInt("generation.vein-size-max",5));int attempts=Math.max(veins,plugin.getConfig().getInt("generation.max-attempts-per-chunk",96));int spacing=Math.max(1,plugin.getConfig().getInt("generation.min-spacing",8));int vertical=Math.max(0,plugin.getConfig().getInt("generation.vertical-spacing",4));boolean replaceOres=plugin.getConfig().getBoolean("generation.replace-standard-ores",false);int placedVeins=0,height=maxY-minY+1;while(attempts-->0&&placedVeins<veins&&remainingPositions>0){int x=(c.getX()<<4)+random.nextInt(16),y=minY+random.nextInt(height),z=(c.getZ()<<4)+random.nextInt(16);if(!replaceable(w.getBlockAt(x,y,z).getType(),w,replaceOres)||!nodeManager.isAreaFree(w.getUID(),x,y,z,spacing,vertical))continue;Material ore=roll(weights);if(ore==null)continue;if(placeVein(c,x,y,z,ore,veinMin,veinMax,minY,maxY,w,replaceOres)){placedVeins++;remainingPositions--;}}}
    private boolean placeVein(Chunk c,int x,int y,int z,Material ore,int min,int max,int minY,int maxY,World w,boolean replaceOres){int wanted=min+random.nextInt(max-min+1);List<int[]> blocks=new ArrayList<>();blocks.add(new int[]{x,y,z});int[][] dirs={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};int guard=wanted*16;while(blocks.size()<wanted&&guard-->0&&remainingPositions>0){int[] base=blocks.get(random.nextInt(blocks.size()));int[] d=dirs[random.nextInt(dirs.length)];int nx=base[0]+d[0],ny=base[1]+d[1],nz=base[2]+d[2];if(ny<minY||ny>maxY||(nx>>4)!=c.getX()||(nz>>4)!=c.getZ())continue;boolean exists=false;for(int[] p:blocks)if(p[0]==nx&&p[1]==ny&&p[2]==nz){exists=true;break;}if(exists)continue;if(!replaceable(w.getBlockAt(nx,ny,nz).getType(),w,replaceOres))continue;blocks.add(new int[]{nx,ny,nz});remainingPositions--;}
        for(int[] p:blocks){if(!nodeManager.isNode(w.getUID(),p[0],p[1],p[2]))nodeManager.addNode(w.getBlockAt(p[0],p[1],p[2]).getLocation(),ore,1);}return !blocks.isEmpty();}
    private Map<Material,Integer> weightsFor(World w){return switch(w.getEnvironment()){case NETHER->netherWeights;case THE_END->endWeights;default->overworldWeights;};}
    private boolean replaceable(Material m,World w,boolean replaceOres){if(!replaceOres&&m.name().endsWith("_ORE"))return false;return switch(w.getEnvironment()){case NETHER->m==Material.NETHERRACK||m==Material.BASALT||m==Material.BLACKSTONE;case THE_END->m==Material.END_STONE;default->{if(m==Material.STONE||m==Material.DEEPSLATE||m==Material.TUFF)yield true;if(!plugin.getConfig().getBoolean("generation.overworld.allow-stone-variants",true))yield false;String n=m.name();yield n.endsWith("_STONE")||n.endsWith("ANDESITE")||n.endsWith("DIORITE")||n.endsWith("GRANITE");}};}
    private Material roll(Map<Material,Integer> weights){int total=0;for(int v:weights.values())total+=Math.max(0,v);if(total<=0)return null;int pick=random.nextInt(total);for(var e:weights.entrySet()){pick-=Math.max(0,e.getValue());if(pick<0)return e.getKey();}return null;}
}
