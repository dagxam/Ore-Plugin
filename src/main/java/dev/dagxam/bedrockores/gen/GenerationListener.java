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

/** Generates configurable normal ores and rare rich ore veins after terrain generation. */
public class GenerationListener implements Listener {
    private final Plugin plugin; private final NodeManager nodeManager; private final Random random=new Random();
    private final Map<Material,Integer> overworldWeights=new LinkedHashMap<>(),netherWeights=new LinkedHashMap<>(),endWeights=new LinkedHashMap<>();
    private final ArrayDeque<ChunkJob> queue=new ArrayDeque<>(); private final Set<ChunkJob> queued=new HashSet<>();
    private BukkitTask queueTask; private boolean queueEnabled; private int chunksPerTick,positionsPerTick,remainingPositions;
    private record ChunkJob(UUID worldId,int x,int z){}
    public GenerationListener(Plugin plugin,NodeManager nodeManager){this.plugin=plugin;this.nodeManager=nodeManager;reloadSettings();}
    public void reloadSettings(){reloadWeights();ConfigurationSection q=plugin.getConfig().getConfigurationSection("generation.queue");queueEnabled=q==null||q.getBoolean("enabled",true);chunksPerTick=Math.max(1,q==null?2:q.getInt("chunks-per-tick",2));positionsPerTick=Math.max(32,q==null?256:q.getInt("positions-per-tick",256));stopQueue();startQueueIfEnabled();}
    public void reloadWeights(){loadWeights("ore-weights",overworldWeights);loadWeights("ore-weights-nether",netherWeights);loadWeights("ore-weights-end",endWeights);if(overworldWeights.isEmpty()){overworldWeights.put(Material.DEEPSLATE_IRON_ORE,6);overworldWeights.put(Material.DEEPSLATE_GOLD_ORE,3);overworldWeights.put(Material.DEEPSLATE_DIAMOND_ORE,1);}if(netherWeights.isEmpty())netherWeights.put(Material.ANCIENT_DEBRIS,1);}
    private void loadWeights(String path,Map<Material,Integer> target){target.clear();ConfigurationSection s=plugin.getConfig().getConfigurationSection(path);if(s==null)return;for(String n:s.getKeys(false))try{Material m=Material.valueOf(n);int w=s.getInt(n);if(w>0&&allowed(m))target.put(m,w);}catch(IllegalArgumentException e){plugin.getLogger().warning("Invalid ore material in "+path+": "+n);}}
    private boolean allowed(Material m){return m==Material.ANCIENT_DEBRIS||m.name().endsWith("_ORE");}
    public void startQueueIfEnabled(){if(queueEnabled&&queueTask==null)queueTask=Bukkit.getScheduler().runTaskTimer(plugin,this::drainQueue,1L,1L);}
    public void stopQueue(){if(queueTask!=null)queueTask.cancel();queueTask=null;queue.clear();queued.clear();}
    @EventHandler public void onChunkLoad(ChunkLoadEvent e){Chunk c=e.getChunk();if(!enabled(c.getWorld()))return;if(nodeManager.isChunkProcessed(c.getWorld(),c.getX(),c.getZ())){nodeManager.processDueRespawnsInChunk(c);return;}if(queueEnabled)offer(c);else generateNow(c);}
    private void offer(Chunk c){ChunkJob j=new ChunkJob(c.getWorld().getUID(),c.getX(),c.getZ());if(queued.add(j))queue.add(j);}
    private void drainQueue(){remainingPositions=positionsPerTick;for(int chunks=chunksPerTick;chunks>0&&remainingPositions>0&&!queue.isEmpty();chunks--){ChunkJob j=queue.poll();queued.remove(j);World w=Bukkit.getWorld(j.worldId());if(w==null||!w.isChunkLoaded(j.x(),j.z())||!enabled(w))continue;Chunk c=w.getChunkAt(j.x(),j.z());if(!nodeManager.isChunkProcessed(w,j.x(),j.z()))generateNow(c);nodeManager.processDueRespawnsInChunk(c);}}
    private void generateNow(Chunk c){generateNormalOres(c);generateRichOres(c);nodeManager.markChunkProcessed(c.getWorld(),c.getX(),c.getZ());}
    private boolean enabled(World w){List<String> worlds=plugin.getConfig().getStringList("enabled-worlds");return worlds.isEmpty()||worlds.contains(w.getName());}

    private void generateNormalOres(Chunk c){
        if(!plugin.getConfig().getBoolean("normal-ores.enabled",true))return;
        World w=c.getWorld();ConfigurationSection ores=plugin.getConfig().getConfigurationSection("normal-ores.types");if(ores==null)return;
        for(String key:ores.getKeys(false)){ConfigurationSection s=ores.getConfigurationSection(key);if(s==null||!s.getBoolean("enabled",true))continue;Material ore;try{ore=Material.valueOf(key);}catch(IllegalArgumentException ignored){plugin.getLogger().warning("Invalid normal ore: "+key);continue;}if(!allowedNormalInWorld(ore,w))continue;
            int veins=Math.max(0,s.getInt("veins-per-chunk",0)),min=Math.max(1,s.getInt("vein-size-min",1)),max=Math.max(min,s.getInt("vein-size-max",min));
            int minY=Math.max(w.getMinHeight(),s.getInt("min-y",w.getMinHeight())),maxY=Math.min(w.getMaxHeight()-1,s.getInt("max-y",w.getMaxHeight()-1));if(maxY<minY)continue;
            int attempts=Math.max(veins,s.getInt("max-attempts",veins*8)),placed=0;
            while(placed<veins&&attempts-->0&&remainingPositions>0){int x=(c.getX()<<4)+random.nextInt(16),y=minY+random.nextInt(maxY-minY+1),z=(c.getZ()<<4)+random.nextInt(16);if(!normalReplaceable(w.getBlockAt(x,y,z).getType(),w))continue;if(placeNormalVein(c,w,x,y,z,ore,min,max,minY,maxY)){placed++;}}
        }
    }
    private boolean placeNormalVein(Chunk c,World w,int x,int y,int z,Material ore,int min,int max,int minY,int maxY){int wanted=min+random.nextInt(max-min+1);List<int[]> blocks=new ArrayList<>();blocks.add(new int[]{x,y,z});int[][] dirs={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};int guard=wanted*20;while(blocks.size()<wanted&&guard-->0&&remainingPositions>0){int[] b=blocks.get(random.nextInt(blocks.size())),d=dirs[random.nextInt(dirs.length)];int nx=b[0]+d[0],ny=b[1]+d[1],nz=b[2]+d[2];if(ny<minY||ny>maxY||(nx>>4)!=c.getX()||(nz>>4)!=c.getZ())continue;boolean exists=false;for(int[] p:blocks)if(p[0]==nx&&p[1]==ny&&p[2]==nz){exists=true;break;}if(exists||!normalReplaceable(w.getBlockAt(nx,ny,nz).getType(),w))continue;blocks.add(new int[]{nx,ny,nz});}
        for(int[] p:blocks){Material placed=normalVariant(ore,w.getBlockAt(p[0],p[1],p[2]).getType());w.getBlockAt(p[0],p[1],p[2]).setType(placed,false);remainingPositions--;if(remainingPositions<=0)break;}return !blocks.isEmpty();}
    private boolean allowedNormalInWorld(Material ore,World w){if(w.getEnvironment()==World.Environment.NETHER)return ore==Material.NETHER_GOLD_ORE||ore==Material.NETHER_QUARTZ_ORE||ore==Material.ANCIENT_DEBRIS;if(w.getEnvironment()==World.Environment.THE_END)return false;return ore!=Material.NETHER_GOLD_ORE&&ore!=Material.NETHER_QUARTZ_ORE&&ore!=Material.ANCIENT_DEBRIS;}
    private Material normalVariant(Material ore,Material base){if(base==Material.DEEPSLATE&&ore.name().startsWith("DEEPSLATE_"))return ore;if(base==Material.DEEPSLATE){String n="DEEPSLATE_"+ore.name();try{return Material.valueOf(n);}catch(IllegalArgumentException ignored){}}if(ore.name().startsWith("DEEPSLATE_"))return Material.valueOf(ore.name().substring("DEEPSLATE_".length()));return ore;}
    private boolean normalReplaceable(Material m,World w){return switch(w.getEnvironment()){case NETHER->m==Material.NETHERRACK||m==Material.BASALT||m==Material.BLACKSTONE;case THE_END->false;default->m==Material.STONE||m==Material.DEEPSLATE||m==Material.TUFF||m==Material.ANDESITE||m==Material.DIORITE||m==Material.GRANITE;};}

    public void generateRichOres(Chunk c){World w=c.getWorld();Map<Material,Integer> weights=weightsFor(w);if(weights.isEmpty())return;int minY=w.getMinHeight(),maxY=w.getMaxHeight()-1;if(plugin.getConfig().isInt("generation.y-min"))minY=Math.max(minY,plugin.getConfig().getInt("generation.y-min"));if(plugin.getConfig().isInt("generation.y-max"))maxY=Math.min(maxY,plugin.getConfig().getInt("generation.y-max"));if(maxY<minY)return;int veins=Math.max(0,plugin.getConfig().getInt("generation.veins-per-chunk",2)),min=Math.max(1,plugin.getConfig().getInt("generation.vein-size-min",3)),max=Math.max(min,plugin.getConfig().getInt("generation.vein-size-max",5)),attempts=Math.max(veins,plugin.getConfig().getInt("generation.max-attempts-per-chunk",96)),spacing=Math.max(1,plugin.getConfig().getInt("generation.min-spacing",8)),vertical=Math.max(0,plugin.getConfig().getInt("generation.vertical-spacing",4));int placed=0;while(attempts-->0&&placed<veins&&remainingPositions>0){int x=(c.getX()<<4)+random.nextInt(16),y=minY+random.nextInt(maxY-minY+1),z=(c.getZ()<<4)+random.nextInt(16);if(!normalReplaceable(w.getBlockAt(x,y,z).getType(),w)||!nodeManager.isAreaFree(w.getUID(),x,y,z,spacing,vertical))continue;Material ore=roll(weights);if(ore!=null&&placeRichVein(c,w,x,y,z,ore,min,max,minY,maxY)){placed++;}}
    }
    private boolean placeRichVein(Chunk c,World w,int x,int y,int z,Material ore,int min,int max,int minY,int maxY){int wanted=min+random.nextInt(max-min+1);List<int[]> blocks=new ArrayList<>();blocks.add(new int[]{x,y,z});int[][] dirs={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};int guard=wanted*20;while(blocks.size()<wanted&&guard-->0&&remainingPositions>0){int[] b=blocks.get(random.nextInt(blocks.size())),d=dirs[random.nextInt(dirs.length)];int nx=b[0]+d[0],ny=b[1]+d[1],nz=b[2]+d[2];if(ny<minY||ny>maxY||(nx>>4)!=c.getX()||(nz>>4)!=c.getZ())continue;boolean exists=false;for(int[] p:blocks)if(p[0]==nx&&p[1]==ny&&p[2]==nz){exists=true;break;}if(exists||!normalReplaceable(w.getBlockAt(nx,ny,nz).getType(),w))continue;blocks.add(new int[]{nx,ny,nz});}
        for(int[] p:blocks){if(remainingPositions<=0)break;Material actual=normalVariant(ore,w.getBlockAt(p[0],p[1],p[2]).getType());if(!nodeManager.isNode(w.getUID(),p[0],p[1],p[2]))nodeManager.addNode(w.getBlockAt(p[0],p[1],p[2]).getLocation(),actual,1);remainingPositions--;}return !blocks.isEmpty();}
    private Map<Material,Integer> weightsFor(World w){return switch(w.getEnvironment()){case NETHER->netherWeights;case THE_END->endWeights;default->overworldWeights;};}
    private Material roll(Map<Material,Integer> weights){int total=0;for(int v:weights.values())total+=Math.max(0,v);if(total<=0)return null;int pick=random.nextInt(total);for(var e:weights.entrySet()){pick-=Math.max(0,e.getValue());if(pick<0)return e.getKey();}return null;}
}
