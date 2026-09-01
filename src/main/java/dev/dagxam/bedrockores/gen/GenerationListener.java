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

/** Generates only rich ore veins. Standard ore generation belongs to vanilla/other plugins. */
public class GenerationListener implements Listener {
 private final Plugin plugin; private final NodeManager nodeManager; private final Random random=new Random();
 private final Map<Material,Integer> overworldWeights=new LinkedHashMap<>(),netherWeights=new LinkedHashMap<>();
 private final ArrayDeque<ChunkJob> queue=new ArrayDeque<>(); private final Set<ChunkJob> queued=new HashSet<>(); private BukkitTask queueTask; private boolean queueEnabled; private int chunksPerTick;
 private record ChunkJob(UUID worldId,int x,int z){}
 public GenerationListener(Plugin plugin,NodeManager nodeManager){this.plugin=plugin;this.nodeManager=nodeManager;reloadSettings();}
 public void reloadSettings(){reloadWeights();ConfigurationSection q=plugin.getConfig().getConfigurationSection("generation.queue");queueEnabled=q==null||q.getBoolean("enabled",true);chunksPerTick=Math.max(1,q==null?2:q.getInt("chunks-per-tick",2));stopQueue();startQueueIfEnabled();}
 public void reloadWeights(){loadWeights("ore-weights",overworldWeights);loadWeights("ore-weights-nether",netherWeights);}
 private void loadWeights(String path,Map<Material,Integer> target){target.clear();ConfigurationSection s=plugin.getConfig().getConfigurationSection(path);if(s==null)return;for(String n:s.getKeys(false))try{Material m=Material.valueOf(n);int w=s.isConfigurationSection(n)?s.getInt(n+".weight",0):s.getInt(n);if(w>0&&(m==Material.ANCIENT_DEBRIS||m.name().endsWith("_ORE")))target.put(m,w);}catch(IllegalArgumentException e){plugin.getLogger().warning("Invalid ore material in "+path+": "+n);}}
 public void startQueueIfEnabled(){if(queueEnabled&&queueTask==null)queueTask=Bukkit.getScheduler().runTaskTimer(plugin,this::drainQueue,1L,1L);}
 public void stopQueue(){if(queueTask!=null)queueTask.cancel();queueTask=null;queue.clear();queued.clear();}
 @EventHandler public void onChunkLoad(ChunkLoadEvent event){Chunk c=event.getChunk();World w=c.getWorld();if(!enabled(w)||!plugin.getConfig().getBoolean("generation.enabled",true))return;if(nodeManager.isChunkProcessed(w,c.getX(),c.getZ())){nodeManager.processDueRespawnsInChunk(c);return;}if(queueEnabled)offer(c);else generateInChunk(c);}
 private void offer(Chunk c){ChunkJob j=new ChunkJob(c.getWorld().getUID(),c.getX(),c.getZ());if(queued.add(j))queue.add(j);}
 private void drainQueue(){for(int i=0;i<chunksPerTick&&!queue.isEmpty();i++){ChunkJob j=queue.poll();queued.remove(j);World w=Bukkit.getWorld(j.worldId());if(w==null||!w.isChunkLoaded(j.x(),j.z())||!enabled(w))continue;Chunk c=w.getChunkAt(j.x(),j.z());if(!nodeManager.isChunkProcessed(w,j.x(),j.z()))generateInChunk(c);nodeManager.processDueRespawnsInChunk(c);}}
 public void generateInChunk(Chunk c){if(!enabled(c.getWorld())||!plugin.getConfig().getBoolean("generation.enabled",true))return;generateRichOres(c);nodeManager.markChunkProcessed(c.getWorld(),c.getX(),c.getZ());}
 private boolean enabled(World w){List<String> worlds=plugin.getConfig().getStringList("enabled-worlds");return worlds.isEmpty()||worlds.contains(w.getName());}
 private int cfgInt(String material,String key,int def){return plugin.getConfig().getInt("rich-ores."+material+"."+key,def);}
 private void generateRichOres(Chunk c){World w=c.getWorld();Map<Material,Integer> weights=w.getEnvironment()==World.Environment.NETHER?netherWeights:overworldWeights;if(weights.isEmpty())return;int minY=w.getMinHeight(),maxY=w.getMaxHeight()-1;if(plugin.getConfig().isInt("generation.y-min"))minY=Math.max(minY,plugin.getConfig().getInt("generation.y-min"));if(plugin.getConfig().isInt("generation.y-max"))maxY=Math.min(maxY,plugin.getConfig().getInt("generation.y-max"));if(maxY<minY)return;int veins=Math.max(0,plugin.getConfig().getInt("generation.veins-per-chunk",2)),spacing=Math.max(1,plugin.getConfig().getInt("generation.min-spacing",8)),vertical=Math.max(0,plugin.getConfig().getInt("generation.vertical-spacing",4)),attempts=Math.max(veins,plugin.getConfig().getInt("generation.max-attempts-per-chunk",96));int placed=0;for(int a=0;a<attempts&&placed<veins;a++){int x=(c.getX()<<4)+random.nextInt(16),y=minY+random.nextInt(maxY-minY+1),z=(c.getZ()<<4)+random.nextInt(16);if(!isValidHost(w.getBlockAt(x,y,z).getType())||!nodeManager.isAreaFree(w.getUID(),x,y,z,spacing,vertical))continue;Material ore=roll(weights);if(ore!=null&&placeRichVein(c,x,y,z,ore,minY,maxY))placed++;}}
 private boolean placeRichVein(Chunk c,int x,int y,int z,Material ore,int minY,int maxY){World w=c.getWorld();String n=ore.name();int minSize=Math.max(1,cfgInt(n,"vein-size-min",3)),maxSize=Math.max(minSize,cfgInt(n,"vein-size-max",5));int wanted=minSize+random.nextInt(maxSize-minSize+1);List<int[]> p=new ArrayList<>();p.add(new int[]{x,y,z});int[][] d={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};int guard=wanted*24;while(p.size()<wanted&&guard-->0){int[] b=p.get(random.nextInt(p.size())),v=d[random.nextInt(d.length)];int nx=b[0]+v[0],ny=b[1]+v[1],nz=b[2]+v[2];if(ny<minY||ny>maxY||(nx>>4)!=c.getX()||(nz>>4)!=c.getZ()||contains(p,nx,ny,nz)||!isValidHost(w.getBlockAt(nx,ny,nz).getType()))continue;p.add(new int[]{nx,ny,nz});}if(p.size()<minSize)return false;for(int[] q:p){if(nodeManager.isNode(w.getUID(),q[0],q[1],q[2]))continue;Material display=oreVariant(ore,w.getBlockAt(q[0],q[1],q[2]).getType());nodeManager.addNode(w.getBlockAt(q[0],q[1],q[2]).getLocation(),display,1);}return true;}
 private boolean contains(List<int[]> p,int x,int y,int z){for(int[] q:p)if(q[0]==x&&q[1]==y&&q[2]==z)return true;return false;}
 private boolean isValidHost(Material m){if(m.isAir()||m==Material.WATER||m==Material.LAVA||!m.isSolid()||m==Material.BEDROCK||m==Material.END_PORTAL_FRAME)return false;if(m.name().endsWith("_ORE")||m==Material.ANCIENT_DEBRIS)return false;String n=m.name();return !n.contains("LEAVES")&&!n.contains("SAPLING")&&!n.contains("CORAL")&&!n.contains("MUSHROOM")&&!n.contains("FLOWER")&&!n.contains("GRASS")&&!n.contains("FERN")&&!n.contains("VINE")&&!n.contains("CROP")&&!n.contains("ROOTS")&&!n.contains("BUSH")&&!n.contains("KELP")&&!n.contains("SEAGRASS")&&!n.contains("BAMBOO")&&!n.contains("CACTUS")&&!n.contains("SUGAR_CANE");}
 private Material oreVariant(Material ore,Material base){if(base==Material.DEEPSLATE&&!ore.name().startsWith("DEEPSLATE_"))try{return Material.valueOf("DEEPSLATE_"+ore.name());}catch(IllegalArgumentException ignored){}return ore;}
 private Material roll(Map<Material,Integer> w){int total=w.values().stream().mapToInt(Integer::intValue).sum();if(total<=0)return null;int pick=random.nextInt(total);for(var e:w.entrySet()){pick-=e.getValue();if(pick<0)return e.getKey();}return null;}
}
