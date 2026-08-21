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

/**
 * Административные команды BedrockOres.
 * Имена команд и permission намеренно остаются английскими.
 * Текст сообщений полностью русифицирован.
 */
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
            sender.sendMessage("§cУ вас нет прав для выполнения этой команды.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§e/bedrockores reload §7— перезагрузить конфигурацию и генерацию");
            sender.sendMessage("§e/bedrockores clearflags [мир|all] §7— очистить флаги обработанных чанков");
            sender.sendMessage("§e/bedrockores regenloaded [мир|all] §7— перегенерировать руды в загруженных чанках");
            sender.sendMessage("§e/bedrockores restartgen [мир|all] §7— полностью перезапустить генерацию");
            sender.sendMessage("§e/bedrockores applyvisuals [мир|all] [force] §7— применить серверный внешний вид руд");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload":
                plugin.reloadConfig();
                generation.reloadSettings();
                sender.sendMessage("§aКонфигурация, параметры генерации и очередь успешно перезагружены.");
                return true;

            case "clearflags": {
                List<World> targets = resolveWorlds(args, 1);
                if (targets.isEmpty()) {
                    sender.sendMessage("§cМир не найден или не разрешён в настройках плагина.");
                    return true;
                }
                for (World world : targets) nodeManager.clearProcessedFlags(world);
                nodeManager.save();
                sender.sendMessage("§aФлаги обработанных чанков очищены для: §f" + names(targets));
                return true;
            }

            case "regenloaded": {
                List<World> targets = resolveWorlds(args, 1);
                if (targets.isEmpty()) {
                    sender.sendMessage("§cМир не найден или не разрешён в настройках плагина.");
                    return true;
                }
                int chunks = 0;
                for (World world : targets) {
                    for (Chunk chunk : world.getLoadedChunks()) {
                        nodeManager.removeRespawnsInChunk(chunk);
                        nodeManager.removeNodesInChunk(chunk);
                        generation.generateInChunk(chunk);
                        nodeManager.markChunkProcessed(world, chunk.getX(), chunk.getZ());
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
                    sender.sendMessage("§cМир не найден или не разрешён в настройках плагина.");
                    return true;
                }
                for (World world : targets) nodeManager.clearProcessedFlags(world);

                int chunks = 0;
                for (World world : targets) {
                    for (Chunk chunk : world.getLoadedChunks()) {
                        nodeManager.removeRespawnsInChunk(chunk);
                        nodeManager.removeNodesInChunk(chunk);
                        generation.generateInChunk(chunk);
                        nodeManager.markChunkProcessed(world, chunk.getX(), chunk.getZ());
                        chunks++;
                    }
                }
                nodeManager.save();
                sender.sendMessage("§aПерезапуск генерации выполнен. Обработано чанков: §f" + chunks + " §7(миры: " + names(targets) + ")");
                return true;
            }

            case "applyvisuals": {
                boolean enabled = plugin.getConfig().getBoolean("визуал.серверный-блок.включено", false);
                boolean force = args.length >= 3 && args[2].equalsIgnoreCase("force");
                if (!enabled && !force) {
                    sender.sendMessage("§eСерверный внешний вид отключён в конфигурации. Включите «визуал.серверный-блок.включено» и выполните /" + label + " reload, либо используйте force.");
                    return true;
                }

                List<World> targets = resolveWorlds(args, 1);
                if (targets.isEmpty()) {
                    sender.sendMessage("§cМир не найден или не разрешён в настройках плагина.");
                    return true;
                }

                int total = 0;
                for (World world : targets) total += nodeManager.applyServerVisualsInWorld(world, true);
                sender.sendMessage("§aСерверный внешний вид применён к узлам: §f" + total + " §7(миры: " + names(targets) + ")");
                return true;
            }

            default:
                sender.sendMessage("§cНеизвестная подкоманда. Используйте /" + label + " help");
                return true;
        }
    }

    private List<World> resolveWorlds(String[] args, int idxFrom) {
        List<String> enabled = plugin.getConfig().getStringList("разрешённые-мирами");
        List<World> targets = new ArrayList<>();
        String name = args.length > idxFrom ? args[idxFrom] : "all";

        if (name.equalsIgnoreCase("all")) {
            for (String worldName : enabled) {
                World world = plugin.getServer().getWorld(worldName);
                if (world != null) targets.add(world);
            }
            return targets;
        }

        World world = plugin.getServer().getWorld(name);
        if (world != null && enabled.contains(world.getName())) targets.add(world);
        return targets;
    }

    private String names(List<World> worlds) {
        List<String> names = new ArrayList<>();
        for (World world : worlds) names.add(world.getName());
        return String.join(", ", names);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> base = List.of("reload", "clearflags", "regenloaded", "restartgen", "applyvisuals", "help");
            StringUtil.copyPartialMatches(args[0], base, out);
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("clearflags")
                || args[0].equalsIgnoreCase("regenloaded")
                || args[0].equalsIgnoreCase("restartgen")
                || args[0].equalsIgnoreCase("applyvisuals"))) {
            List<String> worlds = new ArrayList<>(plugin.getConfig().getStringList("разрешённые-мирами"));
            worlds.add("all");
            StringUtil.copyPartialMatches(args[1], worlds, out);
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("applyvisuals")) {
            StringUtil.copyPartialMatches(args[2], List.of("force"), out);
        }
        return out;
    }
}
