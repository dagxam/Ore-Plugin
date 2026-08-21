package dev.dagxam.bedrockores.gen;

import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** Детерминированный генератор руд с безопасной очередью и загрузкой данных по чанкам. */
public class GenerationListener implements Listener {
    private static final long GENERATOR_SALT=0x4F524556325F4F52L; private static final int VEIN_SEARCH_RADIUS_CHUNKS=1;
    private final Plugin plugin; private final NodeManager nodeManager; private boolean queueEnabled; private int chunksPerTick; private BukkitTask queueTask;
    private final ArrayDeque<long[]> chunkQueue=new ArrayDeque<>(); private final Set<String> queuedKeys=new HashSet<>();
    public GenerationListener(Plugin p,NodeManager n){plugin=p;nodeManager=n;reloadSettings();}
    public void reloadSettings(){ConfigurationSection q=plugin.getConfig().getConfigurationSection("генерация.очередь");queueEnabled=q!=null&&q.getBoolean("включено",true);chunksPerTick=Math.max(1,q==null?2:q.getInt("чанков-за-тик",2));stopQueue();startQueueIfEnabled();}
    public void startQueueIfEnabled(){if(!queueEnabled||queueTask!=null)return;queueTask=Bukkit.getScheduler().runTaskTimer(plugin,this::drainQueue,1L,1L);}
    public void stopQueue(){if(queueTask!=null){queueTask.cancel();queueTask=null;}chunkQueue.clear();queuedKeys.clear();}
    private boolean isEnabledWorld(World w){return w!=null&&plugin.getConfig().getStringList("разрешённые-мирами").contains(w.getName());}
    private void offerChunk(Chunk c){String k=chunkKey(c.getWorld().getUID(),c.getX(),c.getZ());if(queuedKeys.add(k))chunkQueue.add(new long[]{c.getWorld().getUID().getMostSignificantBits(),c.getWorld().getUID().getLeastSignificantBits(),c.getX(),c.getZ()});}
    private void drainQueue(){int b=chunksPerTick;while(b-->0&&!chunkQueue.isEmpty()){long[]e=chunkQueue.poll();UUID w=new UUID(e[0],e[1]);int x=(int)e[2],z=(int)e[3];queuedKeys.remove(chunkKey(w,x,z));World world=Bukkit.getWorld(w);if(world==null||!isEnabledWorld(world)||!world.isChunkLoaded(x,z))continue;Chunk c=world.getChunkAt(x,z);if(!nodeManager.isChunkProcessed(world,x,z)){generateInChunk(c);nodeManager.markChunkProcessed(world,x,z);}nodeManager.processDueRespawnsInChunk(c);}}
    private static String chunkKey(UUID w,int x,int z){return w+":"+x+":"+z;}

    @EventHandler public void onChunkLoad(ChunkLoadEvent e){World w=e.getWorld();if(!isEnabledWorld(w))return;Chunk c=e.getChunk();nodeManager.loadChunkAsync(c,()->afterChunkDataLoaded(c));}
    private void afterChunkDataLoaded(Chunk c){if(c==null||!c.getWorld().isChunkLoaded(c.getX(),c.getZ()))return;World w=c.getWorld();if(!nodeManager.isChunkProcessed(w,c.getX(),c.getZ())){if(queueEnabled)offerChunk(c);else{generateInChunk(c);nodeManager.markChunkProcessed(w,c.getX(),c.getZ());}}nodeManager.processDueRespawnsInChunk(c);}
    @EventHandler public void onChunkUnload(ChunkUnloadEvent e){nodeManager.unloadChunk(e.getChunk());}

    public void generateInChunk(Chunk target){if(target==null)return;World world=target.getWorld();Environment env=world.getEnvironment();if(env!=Environment.NORMAL&&env!=Environment.NETHER)return;ConfigurationSection ores=plugin.getConfig().getConfigurationSection("генерация.руды");if(ores==null)return;int max=Math.max(0,plugin.getConfig().getInt("генерация.максимум-узлов-на-чанк",24));int attempts=Math.max(100,plugin.getConfig().getInt("генерация.максимум-попыток-на-чанк",1800));int minSize=Math.max(1,plugin.getConfig().getInt("генерация.жила.размер-минимум",3));int maxSize=Math.max(minSize,plugin.getConfig().getInt("генерация.жила.размер-максимум",6));int spacing=Math.max(1,plugin.getConfig().getInt("генерация.жила.минимальная-дистанция",4));double density=Math.max(0,plugin.getConfig().getDouble("генерация.общий-множитель-плотности",1));double chance=Math.max(0,plugin.getConfig().getDouble("генерация.шанс-на-блок",.008));List<OreProfile> ps=loadProfiles(ores,env,minSize,maxSize,density);if(ps.isEmpty()||max<=0)return;int tx=target.getX(),tz=target.getZ(),placed=0,centers=0;Set<Long> seen=new HashSet<>();for(int cx=tx-1;cx<=tx+1&&placed<max&&centers<attempts;cx++)for(int cz=tz-1;cz<=tz+1&&placed<max&&centers<attempts;cz++){SplittableRandom r=new SplittableRandom(seed(world,cx,cz));int count=Math.max(1,(int)Math.round(256*chance));for(int i=0;i<count&&placed<max&&centers<attempts;i++,centers++){OreProfile p=pick(ps,r);int x=(cx<<4)+r.nextInt(16),z=(cz<<4)+r.nextInt(16),y=p.minY+r.nextInt(p.maxY-p.minY+1);long id=(((long)x)<<32)^(z&0xffffffffL)^(((long)y)<<48);if(!seen.add(id))continue;int size=p.minSize+r.nextInt(p.maxSize-p.minSize+1);placed+=placeVein(target,p,x,y,z,size,spacing,r);}}}
    private int placeVein(Chunk target,OreProfile p,int x,int y,int z,int size,int spacing,SplittableRandom r){int n=0,cx=target.getX(),cz=target.getZ();for(int i=0;i<size;i++){int bx=x+r.nextInt(-spacing,spacing+1),by=y+r.nextInt(-1,2),bz=z+r.nextInt(-spacing,spacing+1);if((bx>>4)!=cx||(bz>>4)!=cz)continue;BlockCheck b=check(target.getWorld(),bx,by,bz);if(!b.ok)continue;Location l=new Location(target.getWorld(),bx,by,bz);if(nodeManager.isNode(l))continue;nodeManager.addNode(l,p.material,nodeManager.randomHits());n++;}return n;}
    private BlockCheck check(World w,int x,int y,int z){if(y<w.getMinHeight()||y>=w.getMaxHeight())return BlockCheck.NO;Material m=w.getBlockAt(x,y,z).getType();if(m.isAir()||m==Material.WATER||m==Material.LAVA||m==Material.BUBBLE_COLUMN)return BlockCheck.NO;if(w.getEnvironment()==Environment.NETHER)return (m==Material.NETHERRACK||m==Material.BASALT||m==Material.BLACKSTONE)?BlockCheck.YES:BlockCheck.NO;return (m==Material.STONE||m==Material.DEEPSLATE||m==Material.TUFF||m==Material.ANDESITE||m==Material.DIORITE||m==Material.GRANITE||m==Material.SAND||m==Material.RED_SAND||m==Material.GRAVEL)?BlockCheck.YES:BlockCheck.NO;}
    private long seed(World w,int x,int z){long s=w.getSeed()^GENERATOR_SALT;s^=((long)x*341873128712L);s^=((long)z*132897987541L);return s;}
    private List<OreProfile> loadProfiles(ConfigurationSection s,Environment e,int dmin,int dmax,double global){List<OreProfile>out=new ArrayList<>();for(String k:s.getKeys(false)){ConfigurationSection o=s.getConfigurationSection(k);if(o==null||!o.getBoolean("включено",true))continue;try{Material m=Material.valueOf(k);if(e==Environment.NETHER?m!=Material.NETHERITE_SCRAP: m==Material.NETHERITE_SCRAP)continue;int a=o.getInt("минимальный-y",-64),b=o.getInt("максимальный-y",63);if(b<a)continue;out.add(new OreProfile(m,Math.max(1,o.getInt("вес",1)),a,b,Math.max(1,o.getInt("размер-жилы-минимум",dmin)),Math.max(dmin,o.getInt("размер-жилы-максимум",dmax)),Math.max(0,o.getDouble("плотность",1))*global));}catch(Exception ignored){}}return out;}
    private OreProfile pick(List<OreProfile>p,SplittableRandom r){double t=0;for(OreProfile x:p)t+=x.weight*Math.max(.01,x.density);double v=r.nextDouble()*t;for(OreProfile x:p){v-=x.weight*Math.max(.01,x.density);if(v<=0)return x;}return p.getLast();}
    private record OreProfile(Material material,int weight,int minY,int maxY,int minSize,int maxSize,double density){} private enum BlockCheck{YES(true),NO(false);final boolean ok;BlockCheck(boolean o){ok=o;}}
}
