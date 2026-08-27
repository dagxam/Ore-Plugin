package dev.dagxam.bedrockores.cmd;

import dev.dagxam.bedrockores.OrePlugin;
import dev.dagxam.bedrockores.gen.GenerationListener;
import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class BedrockOresCommand implements CommandExecutor, TabCompleter {
    private final OrePlugin plugin;
    private final NodeManager nodeManager;
    private final GenerationListener generation;

    public BedrockOresCommand(OrePlugin plugin, NodeManager nodeManager, GenerationListener generation) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
        this.generation = generation;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("bedrockores.admin")) { sender.sendMessage("§cНет прав."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§e/bedrockores reload §7- перезагрузить конфиг и генерацию");
            sender.sendMessage("§e/bedrockores clearflags [мир|all] §7- очистить флаги чанков");
            sender.sendMessage("§e/bedrockores regenloaded [мир|all] §7- перегенерировать загруженные чанки");
            sender.sendMessage("§e/bedrockores restartgen [мир|all] §7- очистить флаги и перегенерировать");
            sender.sendMessage("§e/bedrockores applyvisuals [мир|all] [force] §7- применить вид богатых руд");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> { plugin.reloadConfig(); generation.reloadSettings(); sender.sendMessage("§aКонфиг и генерация перезагружены."); return true; }
            case "clearflags" -> { List<World> t=resolveWorlds(args,1); if(t.isEmpty()){sender.sendMessage("§cМир не найден или отключён.");return true;} for(World w:t)nodeManager.clearProcessedFlags(w); nodeManager.save(); sender.sendMessage("§aФлаги очищены: §f"+names(t)); return true; }
            case "regenloaded", "restartgen" -> {
                List<World> t=resolveWorlds(args,1); if(t.isEmpty()){sender.sendMessage("§cМир не найден или отключён.");return true;}
                if(args[0].equalsIgnoreCase("restartgen"))for(World w:t)nodeManager.clearProcessedFlags(w);
                int chunks=0; for(World w:t)for(Chunk c:w.getLoadedChunks()){nodeManager.removeRespawnsInChunk(c);nodeManager.removeNodesInChunk(c);generation.generateInChunk(c);nodeManager.markChunkProcessed(w,c.getX(),c.getZ());chunks++;}
                nodeManager.save(); sender.sendMessage("§aПерегенерировано чанков: §f"+chunks+" §7(миры: "+names(t)+")"); return true;
            }
            case "applyvisuals" -> { boolean enabled=plugin.getConfig().getBoolean("visual.server-solid.enabled",false);boolean force=args.length>=3&&args[2].equalsIgnoreCase("force");if(!enabled&&!force){sender.sendMessage("§eВключи visual.server-solid.enabled или используй force.");return true;}List<World> t=resolveWorlds(args,1);if(t.isEmpty()){sender.sendMessage("§cМир не найден или отключён.");return true;}int total=0;for(World w:t)total+=nodeManager.applyServerVisualsInWorld(w,true);sender.sendMessage("§aВид применён к узлам: §f"+total);return true; }
            default -> { sender.sendMessage("§cНеизвестная подкоманда. /"+label+" help"); return true; }
        }
    }
    private List<World> resolveWorlds(String[] args,int idx){List<String> enabled=plugin.getConfig().getStringList("enabled-worlds");List<World> out=new ArrayList<>();String name=args.length>idx?args[idx]:"all";if(name.equalsIgnoreCase("all")){if(enabled.isEmpty())out.addAll(plugin.getServer().getWorlds());else for(String n:enabled){World w=plugin.getServer().getWorld(n);if(w!=null)out.add(w);}return out;}World w=plugin.getServer().getWorld(name);if(w!=null&&(enabled.isEmpty()||enabled.contains(w.getName())))out.add(w);return out;}
    private String names(List<World> worlds){return worlds.stream().map(World::getName).reduce((a,b)->a+", "+b).orElse("");}
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] args){List<String> out=new ArrayList<>();if(args.length==1){StringUtil.copyPartialMatches(args[0],List.of("reload","clearflags","regenloaded","restartgen","applyvisuals","help"),out);return out;}if(args.length==2&&List.of("clearflags","regenloaded","restartgen","applyvisuals").contains(args[0].toLowerCase())){List<String>w=new ArrayList<>(plugin.getConfig().getStringList("enabled-worlds"));if(w.isEmpty())for(World world:plugin.getServer().getWorlds())w.add(world.getName());w.add("all");StringUtil.copyPartialMatches(args[1],w,out);return out;}if(args.length==3&&args[0].equalsIgnoreCase("applyvisuals"))StringUtil.copyPartialMatches(args[2],List.of("force"),out);return out;}
}
