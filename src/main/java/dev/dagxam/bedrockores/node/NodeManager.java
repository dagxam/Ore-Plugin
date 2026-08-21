package dev.dagxam.bedrockores.node;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

/** Оптимизированный менеджер: SQLite, загрузка по чанкам и dirty-сохранение. */
public final class NodeManager {
    private final Plugin plugin;
    private final Random rnd = new Random();
    private final Map<String, NodeData> nodes = new HashMap<>();
    private final Map<String, RespawnData> respawns = new HashMap<>();
    private final Map<UUID, Set<Long>> processedChunks = new HashMap<>();
    private final Map<UUID, Map<Long, Set<String>>> nodesByChunk = new HashMap<>();
    private final Map<UUID, Map<Long, Set<String>>> respawnsByChunk = new HashMap<>();
    private final Set<Long> loadedChunks = new HashSet<>();
    private final Map<UUID, Set<Long>> dbProcessedCache = new HashMap<>();
    private final Map<String, NodeEntry> dirtyNodes = new HashMap<>(), deletedNodes = new HashMap<>();
    private final Map<String, RespawnEntry> dirtyRespawns = new HashMap<>(), deletedRespawns = new HashMap<>();
    private final Set<ChunkEntry> dirtyProcessed = new HashSet<>();
    private final Set<String> warnedDisplayMaterials = new HashSet<>();
    private final File legacyFile;
    private NodeDatabase database;

    public NodeManager(Plugin plugin) { this.plugin = plugin; this.legacyFile = new File(plugin.getDataFolder(), "nodes.yml"); }

    public static String key(Location l) { return key(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ()); }
    public static String key(UUID w,int x,int y,int z){ return w+":"+x+":"+y+":"+z; }
    public static long chunkKey(int x,int z){ return (((long)x)<<32) ^ (z & 0xffffffffL); }
    private static long loadedKey(UUID w,int x,int z){ return chunkKey(w.hashCode() ^ x, z); }

    public void load() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) throw new IllegalStateException("Не удалось создать папку данных");
            database = new NodeDatabase(plugin);
            if (legacyFile.exists()) migrateLegacyYaml();
            plugin.getLogger().info("Хранилище рудных узлов SQLite готово. Узлы будут загружаться по мере загрузки чанков.");
        } catch (Exception ex) { throw new IllegalStateException("Не удалось открыть SQLite-хранилище BedrockOres", ex); }
    }

    private void migrateLegacyYaml() throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(legacyFile);
        List<NodeEntry> ns=new ArrayList<>(); List<RespawnEntry> rs=new ArrayList<>(); List<ChunkEntry> pcs=new ArrayList<>();
        ConfigurationSection n=y.getConfigurationSection("nodes");
        if(n!=null) for(String id:n.getKeys(false)){ ConfigurationSection s=n.getConfigurationSection(id); if(s==null)continue; try{UUID w=UUID.fromString(Objects.requireNonNull(s.getString("world"))); int x=s.getInt("x"),yy=s.getInt("y"),z=s.getInt("z"),h=s.getInt("hits"); ns.add(new NodeEntry(w,x,yy,z,Material.valueOf(Objects.requireNonNull(s.getString("type"))),h,s.getInt("maxHits",h)));}catch(Exception ignored){} }
        ConfigurationSection r=y.getConfigurationSection("respawns");
        if(r!=null) for(String id:r.getKeys(false)){ ConfigurationSection s=r.getConfigurationSection(id); if(s==null)continue; try{rs.add(new RespawnEntry(UUID.fromString(Objects.requireNonNull(s.getString("world"))),s.getInt("x"),s.getInt("y"),s.getInt("z"),Material.valueOf(Objects.requireNonNull(s.getString("type"))),s.getLong("dueAt")));}catch(Exception ignored){} }
        ConfigurationSection p=y.getConfigurationSection("processedChunks");
        if(p!=null) for(String ws:p.getKeys(false)) try{UUID w=UUID.fromString(ws); for(String v:p.getStringList(ws)){String[] a=v.split(":"); if(a.length==2) pcs.add(new ChunkEntry(w,Integer.parseInt(a[0]),Integer.parseInt(a[1])));}}catch(Exception ignored){}
        database.saveDirty(ns,List.of(),rs,List.of(),pcs);
        File backup=new File(legacyFile.getParentFile(),"nodes.yml.migrated-backup"); if(!legacyFile.renameTo(backup)) plugin.getLogger().warning("SQLite-миграция завершена, но старый nodes.yml не удалось переименовать.");
        plugin.getLogger().info("Перенесено в SQLite: узлов="+ns.size()+", респавнов="+rs.size()+", чанков="+pcs.size());
    }

    public void loadChunkAsync(Chunk chunk, Runnable done) {
        if(chunk==null||database==null){done.run();return;} UUID w=chunk.getWorld().getUID(); int cx=chunk.getX(),cz=chunk.getZ(); long lk=loadedKey(w,cx,cz);
        if(loadedChunks.contains(lk)){done.run();return;}
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try {
                List<NodeEntry> ns=database.loadNodes(w,cx,cz); List<RespawnEntry> rs=database.loadDueRespawns(w,cx,cz,System.currentTimeMillis()); boolean processed=database.isProcessed(w,cx,cz);
                Bukkit.getScheduler().runTask(plugin,()->{ if(!chunk.getWorld().isChunkLoaded(cx,cz))return; applyLoadedChunk(w,cx,cz,ns,rs,processed); done.run(); });
            } catch(Exception ex){ plugin.getLogger().severe("Не удалось загрузить данные чанка "+cx+","+cz+": "+ex.getMessage()); Bukkit.getScheduler().runTask(plugin,done); }
        });
    }
    private void applyLoadedChunk(UUID w,int cx,int cz,List<NodeEntry> ns,List<RespawnEntry> rs,boolean processed){
        long ck=chunkKey(cx,cz); for(NodeEntry e:ns){String k=key(e.world(),e.x(),e.y(),e.z()); nodes.put(k,new NodeData(e.type(),e.hits(),e.maxHits())); index(nodesByChunk,w,ck,k);} for(RespawnEntry e:rs){String k=key(e.world(),e.x(),e.y(),e.z()); respawns.put(k,new RespawnData(e.type(),e.dueAtMillis())); index(respawnsByChunk,w,ck,k);} if(processed){processedChunks.computeIfAbsent(w,a->new HashSet<>()).add(ck);dbProcessedCache.computeIfAbsent(w,a->new HashSet<>()).add(ck);} loadedChunks.add(loadedKey(w,cx,cz));
    }
    public void unloadChunk(Chunk c){ if(c==null)return; UUID w=c.getWorld().getUID(); long ck=chunkKey(c.getX(),c.getZ()); removeChunkMaps(nodes,nodesByChunk,w,ck); removeChunkMaps(respawns,respawnsByChunk,w,ck); loadedChunks.remove(loadedKey(w,c.getX(),c.getZ())); }
    private static void removeChunkMaps(Map<String,?> data,Map<UUID,Map<Long,Set<String>>> idx,UUID w,long ck){Map<Long,Set<String>> m=idx.get(w);if(m==null)return;Set<String>s=m.remove(ck);if(s!=null)for(String k:s)data.remove(k);if(m.isEmpty())idx.remove(w);}

    public boolean isNode(UUID w,int x,int y,int z){return nodes.containsKey(key(w,x,y,z));}
    public boolean isNode(Location l){return l!=null&&l.getWorld()!=null&&nodes.containsKey(key(l));}
    public NodeData getNode(Location l){return l==null||l.getWorld()==null?null:nodes.get(key(l));}
    public void addNode(Location l,Material ore,int hits){if(l==null||l.getWorld()==null||ore==null)return;String k=key(l);int h=Math.max(1,hits);nodes.put(k,new NodeData(ore,h,h));index(nodesByChunk,l.getWorld().getUID(),chunkKey(l.getBlockX()>>4,l.getBlockZ()>>4),k);NodeEntry e=new NodeEntry(l.getWorld().getUID(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),ore,h,h);dirtyNodes.put(k,e);deletedNodes.remove(k);Material p=ore==Material.NETHERITE_SCRAP?Material.ANCIENT_DEBRIS:ore;if(serverSolidEnabled()){Material d=displayFor(ore);if(d!=null)p=d;}l.getBlock().setType(p,false);}
    public void removeNode(Location l){if(l==null||l.getWorld()==null)return;String k=key(l);NodeData d=nodes.remove(k);if(d!=null){removeIndex(nodesByChunk,l.getWorld().getUID(),chunkKey(l.getBlockX()>>4,l.getBlockZ()>>4),k);deletedNodes.put(k,new NodeEntry(l.getWorld().getUID(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),d.oreMaterial,d.hitsRemaining,d.maxHits));dirtyNodes.remove(k);}}

    public void markChunkProcessed(World w,int x,int z){if(w==null)return;long ck=chunkKey(x,z);processedChunks.computeIfAbsent(w.getUID(),a->new HashSet<>()).add(ck);dbProcessedCache.computeIfAbsent(w.getUID(),a->new HashSet<>()).add(ck);dirtyProcessed.add(new ChunkEntry(w.getUID(),x,z));}
    public boolean isChunkProcessed(World w,int x,int z){if(w==null)return false;Set<Long>s=processedChunks.get(w.getUID());return s!=null&&s.contains(chunkKey(x,z));}
    public void clearProcessedFlags(World w){if(w!=null)processedChunks.remove(w.getUID());}
    public void clearAllProcessedFlags(){processedChunks.clear();}
    public int removeNodesInChunk(Chunk c){if(c==null)return 0;Map<Long,Set<String>> m=nodesByChunk.get(c.getWorld().getUID());if(m==null)return 0;Set<String>s=m.get(chunkKey(c.getX(),c.getZ()));if(s==null)return 0;int n=0;for(String k:new ArrayList<>(s)){NodeData d=nodes.remove(k);if(d!=null)n++;}m.remove(chunkKey(c.getX(),c.getZ()));return n;}
    public int removeRespawnsInChunk(Chunk c){if(c==null)return 0;Map<Long,Set<String>>m=respawnsByChunk.get(c.getWorld().getUID());if(m==null)return 0;Set<String>s=m.get(chunkKey(c.getX(),c.getZ()));if(s==null)return 0;int n=0;for(String k:new ArrayList<>(s))if(respawns.remove(k)!=null)n++;m.remove(chunkKey(c.getX(),c.getZ()));return n;}

    public void scheduleRespawn(Location l,Material ore){if(l==null||l.getWorld()==null||ore==null||!plugin.getConfig().getBoolean("возрождение.включено",true))return;long due=System.currentTimeMillis()+Math.max(0L,plugin.getConfig().getLong("возрождение.задержка-секунд",3600L))*1000L;String k=key(l);RespawnData d=new RespawnData(ore,due);respawns.put(k,d);index(respawnsByChunk,l.getWorld().getUID(),chunkKey(l.getBlockX()>>4,l.getBlockZ()>>4),k);dirtyRespawns.put(k,new RespawnEntry(l.getWorld().getUID(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),ore,due));deletedRespawns.remove(k);}
    public void tickRespawns(){long now=System.currentTimeMillis();for(String k:new ArrayList<>(respawns.keySet())){RespawnData d=respawns.get(k);if(d==null||!d.isDue(now))continue;Location l=locationFromKey(k);if(l==null||!l.getWorld().isChunkLoaded(l.getBlockX()>>4,l.getBlockZ()>>4))continue;addNode(l,d.oreMaterial,randomHits());removeRespawn(k,l,d);}}
    public void processDueRespawnsInChunk(Chunk c){if(c==null)return;loadChunkAsync(c,()->tickRespawns());}
    private void removeRespawn(String k,Location l,RespawnData d){respawns.remove(k);removeIndex(respawnsByChunk,l.getWorld().getUID(),chunkKey(l.getBlockX()>>4,l.getBlockZ()>>4),k);deletedRespawns.put(k,new RespawnEntry(l.getWorld().getUID(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),d.oreMaterial,d.dueAtMillis));dirtyRespawns.remove(k);}

    public record NodeEntry(UUID world,int x,int y,int z,Material type,int hits,int maxHits){}
    public record RespawnEntry(UUID world,int x,int y,int z,Material type,long dueAtMillis){}
    public record ChunkEntry(UUID world,int x,int z){}
    public record SaveSnapshot(List<NodeEntry> nodes,Map<UUID,List<String>> processedChunks,List<RespawnEntry> respawns){}
    public SaveSnapshot createSnapshot(){return new SaveSnapshot(List.of(),Map.of(),List.of());}
    public void saveSnapshot(SaveSnapshot ignored){save();}
    public void save(){
        if(database==null)return; List<NodeEntry> upN;List<NodeEntry>delN;List<RespawnEntry>upR;List<RespawnEntry>delR;List<ChunkEntry>pc;
        synchronized(this){upN=new ArrayList<>(dirtyNodes.values());delN=new ArrayList<>(deletedNodes.values());upR=new ArrayList<>(dirtyRespawns.values());delR=new ArrayList<>(deletedRespawns.values());pc=new ArrayList<>(dirtyProcessed);dirtyNodes.clear();deletedNodes.clear();dirtyRespawns.clear();deletedRespawns.clear();dirtyProcessed.clear();}
        if(upN.isEmpty()&&delN.isEmpty()&&upR.isEmpty()&&delR.isEmpty()&&pc.isEmpty())return;
        try{database.saveDirty(upN,delN,upR,delR,pc);}catch(Exception ex){plugin.getLogger().severe("Не удалось сохранить изменённые данные руд: "+ex.getMessage());synchronized(this){for(NodeEntry e:upN)dirtyNodes.put(key(e.world(),e.x(),e.y(),e.z()),e);for(NodeEntry e:delN)deletedNodes.put(key(e.world(),e.x(),e.y(),e.z()),e);for(RespawnEntry e:upR)dirtyRespawns.put(key(e.world(),e.x(),e.y(),e.z()),e);for(RespawnEntry e:delR)deletedRespawns.put(key(e.world(),e.x(),e.y(),e.z()),e);dirtyProcessed.addAll(pc);}}
    }
    public void close(){save();try{if(database!=null)database.close();}catch(SQLException ex){plugin.getLogger().warning("Не удалось закрыть SQLite: "+ex.getMessage());}}

    private static void index(Map<UUID,Map<Long,Set<String>>>i,UUID w,long c,String k){i.computeIfAbsent(w,a->new HashMap<>()).computeIfAbsent(c,a->new HashSet<>()).add(k);}
    private static void removeIndex(Map<UUID,Map<Long,Set<String>>>i,UUID w,long c,String k){Map<Long,Set<String>>m=i.get(w);if(m==null)return;Set<String>s=m.get(c);if(s==null)return;s.remove(k);if(s.isEmpty())m.remove(c);if(m.isEmpty())i.remove(w);}
    private Location locationFromKey(String k){String[]p=k.split(":");if(p.length!=4)return null;try{World w=Bukkit.getWorld(UUID.fromString(p[0]));return w==null?null:new Location(w,Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));}catch(Exception e){return null;}}
    public int randomHits(){int min=Math.max(1,plugin.getConfig().getInt("узел.ударов-минимум",9));int max=Math.max(min,plugin.getConfig().getInt("узел.ударов-максимум",20));return min+rnd.nextInt(max-min+1);}
    private boolean serverSolidEnabled(){return plugin.getConfig().getBoolean("визуал.серверный-блок.включено",false);}
    private Material displayFor(Material ore){String raw=plugin.getConfig().getString("визуал.серверный-блок.соответствия."+ore.name());if(raw==null||raw.isBlank())return null;try{Material m=Material.valueOf(raw.toUpperCase(Locale.ROOT));return m.isBlock()?m:null;}catch(Exception e){if(warnedDisplayMaterials.add(raw))plugin.getLogger().warning("Неизвестный визуальный материал: "+raw);return null;}}
    public int applyServerVisualsInWorld(World w,boolean loadedOnly){if(w==null||!serverSolidEnabled())return 0;Map<Long,Set<String>>m=nodesByChunk.get(w.getUID());if(m==null)return 0;int n=0;for(Set<String>s:m.values())for(String k:s){NodeData d=nodes.get(k);Location l=locationFromKey(k);if(d==null||l==null||loadedOnly&&!w.isChunkLoaded(l.getBlockX()>>4,l.getBlockZ()>>4))continue;Material v=displayFor(d.oreMaterial);if(v!=null){l.getBlock().setType(v,false);n++;}}return n;}
    public int applyServerVisualsForAllNodes(boolean loadedOnly){int n=0;for(World w:Bukkit.getWorlds())n+=applyServerVisualsInWorld(w,loadedOnly);return n;}
}
