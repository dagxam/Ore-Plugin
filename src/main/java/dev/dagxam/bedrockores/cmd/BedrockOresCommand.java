package dev.dagxam.bedrockores.cmd;

import dev.dagxam.bedrockores.BedrockOresPlugin;
import dev.dagxam.bedrockores.gen.GenerationListener;
import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
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
            sender.sendMessage("§e/bedrockores reload §7- перезагрузить конфиг и веса руд");
            sender.sendMessage("§e/bedrockores clearflags [мир|all] §7- очистить флаги обработанных чанков");
            sender.sendMessage("§e/bedrockores regenloaded [мир|all] §7- перегенерировать узлы в загруженных чанках");
            sender.sendMessage("§e/bedrockores restartgen [мир|all] §7- clearflags + regenloaded");
            sender.sendMessage("§e/bedrockores visdebug [материал] [сек] §7- показать клиентский фейк-блок на целевом блоке");
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
                    sender.sendMessage("§cМир не найден или не включён в enabled-worlds.");
                    return true;
                }
                for (World w : targets) nodeManager.clearProcessedFlags(w);
                nodeManager.save();
                sender.sendMessage("§aОчищены флаги обработанных чанков для: §f" + names(targets));
                return true;
            }
            case "regenloaded": {
                List<World> targets = resolveWorlds(args, 1);
                if (targets.isEmpty()) {
                    sender.sendMessage("§cМир не найден или не включён в enabled-worlds.");
                    return true;
                }
                int chunks = 0;
                for (World w : targets) {
                    for (Chunk c : w.getLoadedChunks()) {
                        nodeManager.removeRespawnsInChunk(c);
                        nodeManager.removeNodesInChunk(c);
                        generation.queueChunk(c); // лениво, через очередь
                        chunks++;
                    }
                }
                nodeManager.save();
                sender.sendMessage("§aПерегенерировано загруженных чанков (в очередь): §f" + chunks + " §7(миры: " + names(targets) + ")");
                return true;
            }
            case "restartgen": {
                List<World> targets = resolveWorlds(args, 1);
                if (targets.isEmpty()) {
                    sender.sendMessage("§cМир не найден или не включён в enabled-worlds.");
                    return true;
                }
                for (World w : targets) nodeManager.clearProcessedFlags(w);

                int chunks = 0;
                for (World w : targets) {
                    for (Chunk c : w.getLoadedChunks()) {
                        nodeManager.removeRespawnsInChunk(c);
                        nodeManager.removeNodesInChunk(c);
                        generation.queueChunk(c);
                        chunks++;
                    }
                }
                nodeManager.save();
                sender.sendMessage("§aРестарт генерации выполнен (в очередь). Чанков: §f" + chunks + " §7(миры: " + names(targets) + ")");
                return true;
            }
            case "visdebug": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cЭта команда только для игрока.");
                    return true;
                }
                Player p = (Player) sender;

                // Материал и длительность
                String defName = plugin.getConfig().getString("visual.fakeblock.default", "LIGHT_BLUE_STAINED_GLASS");
                Material mat = materialOrDefault(args.length >= 2 ? args[1] : defName, defName);
                int seconds = 5;
                if (args.length >= 3) {
                    try { seconds = Math.max(1, Integer.parseInt(args[2])); } catch (Exception ignored) {}
                }

                Location target = p.getTargetBlockExact(8) != null ? p.getTargetBlockExact(8).getLocation() : null;
                if (target == null) {
                    p.sendMessage("§cНет целевого блока в радиусе 8.");
                    return true;
                }

                BlockData fake = mat.createBlockData();
                BlockData real = target.getBlock().getBlockData();

                // Показать фейковый блок этому игроку
                p.sendBlockChange(target, fake);
                p.sendMessage("§avisdebug: §7" + mat.name() + " §fна §7" +
                        target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ() +
                        " §7на §f" + seconds + "§7с.");

                // Вернуть реальный вид через N секунд
                int delay = seconds * 20;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    // если игрок в другом мире — всё равно отправим, клиент поправит сам
                    p.sendBlockChange(target, real);
                }, delay);
                return true;
            }
            default:
                sender.sendMessage("§cНеизвестная подкоманда. /" + label + " help");
                return true;
        }
    }

    private Material materialOrDefault(String name, String defName) {
        try { return Material.valueOf(name.toUpperCase()); }
        catch (Exception e) {
            try { return Material.valueOf(defName.toUpperCase()); }
            catch (Exception ignored) { return Material.LIGHT_BLUE_STAINED_GLASS; }
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
            List<String> base = List.of("reload", "clearflags", "regenloaded", "restartgen", "visdebug", "help");
            StringUtil.copyPartialMatches(args[0], base, out);
            return out;
        }
        if (args.length == 2) {
            if ("clearflags".equalsIgnoreCase(args[0]) || "regenloaded".equalsIgnoreCase(args[0]) || "restartgen".equalsIgnoreCase(args[0])) {
                List<String> worlds = new ArrayList<>(plugin.getConfig().getStringList("enabled-worlds"));
                worlds.add("all");
                StringUtil.copyPartialMatches(args[1], worlds, out);
                return out;
            }
            if ("visdebug".equalsIgnoreCase(args[0])) {
                List<String> mats = Arrays.asList(
