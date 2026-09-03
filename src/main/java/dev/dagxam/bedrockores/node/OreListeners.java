package dev.dagxam.bedrockores.node;

import org.bukkit.BlockChangeDelegate;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Rich ore nodes: every mining swing pays ore; after the last hit the node becomes bedrock. */
public class OreListeners implements Listener {
    private static final String USE_PERMISSION = "bedrockores.use";
    private final Plugin plugin;
    private final NodeManager nm;
    private final Random random = new Random();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();

    public OreListeners(Plugin plugin, NodeManager nm) {
        this.plugin = plugin;
        this.nm = nm;
    }

    /**
     * Prevent Bukkit/Minecraft from breaking a rich node normally. Actual hits
     * are counted from arm-swing packets in onAnimation(), so holding the mouse
     * button produces repeated hits without ever destroying the block first.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (!nm.isNode(event.getBlock().getLocation())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!canMine(player)) return;

        Block target = player.getTargetBlockExact(6, FluidCollisionMode.NEVER);
        if (target == null || !nm.isNode(target.getLocation())) return;

        long now = System.currentTimeMillis();
        long intervalMillis = Math.max(1L,
                plugin.getConfig().getLong("performance.hold-hit-interval-ticks", 5L)) * 50L;
        long previous = lastHitAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < intervalMillis) return;
        lastHitAt.put(player.getUniqueId(), now);

        NodeData data = nm.getNode(target.getLocation());
        if (data != null) mineHit(player, target, data);
    }

    private boolean canMine(Player player) {
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendActionBar(net.kyori.adventure.text.Component.text("Нет прав: " + USE_PERMISSION));
            return false;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return false;

        Material tool = player.getInventory().getItemInMainHand().getType();
        return tool == Material.DIAMOND_PICKAXE || tool == Material.NETHERITE_PICKAXE;
    }

    private int cfg(String ore, String key, int def) {
        return Math.max(0, plugin.getConfig().getInt("rich-ores." + ore + "." + key, def));
    }

    private void mineHit(Player player, Block block, NodeData data) {
        String ore = baseOreName(data.oreMaterial);
        int min = Math.max(1, cfg(ore, "drops-min", plugin.getConfig().getInt("drops.min-per-node", 1)));
        int max = Math.max(min, cfg(ore, "drops-max", plugin.getConfig().getInt("drops.max-per-node", 1)));
        int amount = min + random.nextInt(max - min + 1);

        Material drop = dropFor(data.oreMaterial);
        if (drop != null) give(player, block, new ItemStack(drop, amount));

        int xp = Math.max(0, cfg(ore, "xp-per-hit", 0));
        if (xp > 0) {
            if ("orb".equalsIgnoreCase(plugin.getConfig().getString("performance.xp-mode", "direct"))) {
                ExperienceOrb orb = block.getWorld().spawn(
                        block.getLocation().add(.5, .5, .5), ExperienceOrb.class);
                orb.setExperience(xp);
            } else {
                player.giveExp(xp);
            }
        }

        data.hitsRemaining--;
        if (data.hitsRemaining <= 0) {
            Location location = block.getLocation();
            nm.removeNode(location);
            block.setType(depletedMaterial(), false);

            if (plugin.getConfig().getBoolean("respawn.enabled", false)) {
                nm.scheduleRespawn(location, data.oreMaterial);
            }

            block.getWorld().playSound(location.add(.5, .5, .5), Sound.BLOCK_STONE_BREAK, 1f, 1f);
        } else {
            block.getWorld().playSound(
                    block.getLocation().add(.5, .5, .5), Sound.BLOCK_STONE_HIT, 1f, 1f);
        }
    }

    private String baseOreName(Material material) {
        String name = material.name();
        return name.startsWith("DEEPSLATE_") ? name.substring("DEEPSLATE_".length()) : name;
    }

    private Material depletedMaterial() {
        String configured = plugin.getConfig().getString("generation.depleted-block", "BEDROCK");
        try {
            return Material.valueOf(configured);
        } catch (Exception ignored) {
            return Material.BEDROCK;
        }
    }

    private void give(Player player, Block block, ItemStack item) {
        if (plugin.getConfig().getBoolean("performance.direct-item-to-inventory", true)) {
            for (ItemStack rest : player.getInventory().addItem(item).values()) {
                block.getWorld().dropItemNaturally(block.getLocation().add(.5, .5, .5), rest);
            }
        } else {
            block.getWorld().dropItemNaturally(block.getLocation().add(.5, .5, .5), item);
        }
    }

    private Material dropFor(Material ore) {
        return switch (ore) {
            case DEEPSLATE_DIAMOND_ORE, DIAMOND_ORE -> Material.DIAMOND;
            case DEEPSLATE_EMERALD_ORE, EMERALD_ORE -> Material.EMERALD;
            case DEEPSLATE_REDSTONE_ORE, REDSTONE_ORE -> Material.REDSTONE;
            case DEEPSLATE_LAPIS_ORE, LAPIS_ORE -> Material.LAPIS_LAZULI;
            case DEEPSLATE_COAL_ORE, COAL_ORE -> Material.COAL;
            case DEEPSLATE_COPPER_ORE, COPPER_ORE -> Material.RAW_COPPER;
            case DEEPSLATE_IRON_ORE, IRON_ORE -> Material.RAW_IRON;
            case DEEPSLATE_GOLD_ORE, GOLD_ORE -> Material.RAW_GOLD;
            case NETHER_GOLD_ORE -> Material.GOLD_NUGGET;
            case NETHER_QUARTZ_ORE -> Material.QUARTZ;
            case ANCIENT_DEBRIS, NETHERITE_SCRAP -> Material.NETHERITE_SCRAP;
            default -> null;
        };
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> nm.isNode(block.getLocation()));
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> nm.isNode(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (nm.isNode(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (nm.isNode(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
