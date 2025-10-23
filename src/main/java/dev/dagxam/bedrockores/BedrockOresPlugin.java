package dev.dagxam.bedrockores.cmd;

import dev.dagxam.bedrockores.BedrockOresPlugin;
import dev.dagxam.bedrockores.gen.GenerationListener;
import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class BedrockOresCommand implements CommandExecutor, TabCompleter {

    private final BedrockOresPlugin plugin;
    private final NodeManager nodeManager;
    private final GenerationListener generation;

    public BedrockOresCommand(BedrockOresPlugin plugin, NodeManager nodeManager, GenerationListener generation) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
        this.generation = generation;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("bedrockores.admin")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§e/bedrockores reload §7- перезагрузить конфиг");
            sender.sendMessage("§e/bedrockores clearflags [мир|all] §7- очистить флаги обработанных чанков");
            sender.sendMessage("§e/bedrockores regenloaded [мир|all] §7- перегенерировать узлы в загруженных чанках");
            sender.sendMessage("§e/bedrockores restartgen [мир|all] §7- clearflags + regenloaded");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload": {
                plugin.reloadConfig();
                generation.reloadWeights();
                sender.sendMessage("§aКонфиг и веса спавна перезагружены.");
                return true;
            }
            case "clearflags": {
                List<World> targets = resolveWorlds(args, 1);
                if (targets.isEmpty()) {
                    sender.sendMessage("§cМир не найден.");
                    return true;
                }
                for (World w : targets) {
                    nodeManager.clearProcessedFlags(w);
                }
                nodeManager.save();
                sender.sendMessage("§aОчищены флаги обработанных чанков для: §f" + names(targets));
                return true;
            }
            case "regenloaded": {
                List<World> targets = resolveWorlds(args, 1);
                if (targets.isEmpty()) {
                    sender.sendMessage("§cМир не найден.");
                    return true;
                }
                int chunks = 0;
                for (World w : targets) {
                    for (Chunk c : w.getLoadedChunks()) {
                        nodeManager.removeRespawnsInChunk(c);
                        nodeManager.removeNodesInChunk(c);
                        generation.generateInChunk(c);
                        nodeManager.markChunkProcessed(w, c.getX(), c.getZ());
                        chunks++;
                    }
                }
                nodeManager.save();
                sender.sendMessage("§aПерегенерировано загруженных чанков: §f" + chunks + " §7(миры: " + names(targets) + ")");
                return true;
            }
            case "restartgen": {
                List<World> targets = resolveWorlds(args, 1);
                if (targets.isEmpty()) {
                    sender.sendMessage("§cМир не найден.");
                    return true;
                }
                for (World w : targets) nodeManager.clearProcessedFlags(w);

                int chunks = 0;
                for (World w : targets) {
                    for (Chunk c : w.getLoadedChunks()) {
                        nodeManager.removeRespawnsInChunk(c);
                        nodeManager.removeNodesInChunk(c);
                        generation.generateInChunk(c);
                        nodeManager.markChunkProcessed(w, c.getX(), c.getZ());
                        chunks++;
                    }
                }
                nodeManager.save();
                sender.sendMessage("§aРестарт генерации выполнен. Чанков: §f" + chunks + " §7(миры: " + names(targets) + ")");
                return true;
            }
            default:
                sender.sendMessage("§cНеизвестная подкоманда. /" + label + " help");
                return true;
        }
    }

    private List<World> resolveWorlds(String[] args, int idxFrom) {
        List<String> enabled = plugin.getConfig().getStringList("enabled-worlds");
        List<World> targets = new ArrayList<>();
        String name = (args.length > idxFrom) ? args[idxFrom] : "all";
        if (name.equalsIgnoreCase("all")) {
            for (String wname : enabled) {
                World w = plugin.getServer().getWorld(wname);
                if (w != null) targets.add(w);
            }
            return targets;
        }
        World w = plugin.getServer().getWorld(name);
        if (w != null && enabled.contains(w.getName())) targets.add(w);
        return targets;
    }

    private String names(List<World> worlds) {
        List<String> n = new ArrayList<>();
        for (World w : worlds) n.add(w.getName());
        return String.join(", ", n);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> base = List.of("reload", "clearflags", "regenloaded", "restartgen", "help");
            StringUtil.copyPartialMatches(args[0], base, out);
            return out;
        }
        if (args.length == 2) {
            List<String> worlds = new ArrayList<>(plugin.getConfig().getStringList("enabled-worlds"));
            worlds.add("all");
            StringUtil.copyPartialMatches(args[1], worlds, out);
            return out;
        }
        return out;
    }
}
