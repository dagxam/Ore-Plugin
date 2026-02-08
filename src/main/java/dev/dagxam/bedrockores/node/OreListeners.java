package dev.dagxam.bedrockores.node;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class OreListeners implements Listener {
    private static final String USE_PERMISSION = "bedrockores.use";

    private final Plugin plugin;
    private final NodeManager nm;
    private final Random rnd = new Random();

    private static class HoldSession {
        final Location loc;
        long lastSeenNs;
        HoldSession(Location loc, long lastSeenNs) { this.loc = loc; this.lastSeenNs = lastSeenNs; }
    }

    private final Map<UUID, HoldSession> holds = new HashMap<>();

    public OreListeners(Plugin plugin, NodeManager nm) {
        this.plugin = plugin;
        this.nm = nm;

        int intervalTicks = Math.max(1, plugin.getConfig().getInt("performance.hold-hit-interval-ticks", 4));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHolds, intervalTicks, intervalTicks);
    }

    private boolean isAllowedPickaxe(ItemStack tool) {
        if (tool == null) return false;
        Material t = tool.getType();
        return t == Material.DIAMOND_PICKAXE || t == Material.NETHERITE_PICKAXE;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (!nm.isNode(block.getLocation())) return;

        Player p = e.getPlayer();

        if (!p.hasPermission(USE_PERMISSION)) {
            e.setCancelled(true);
            refreshClientBlock(block);
            p.sendActionBar(Component.text("Нет прав: " + USE_PERMISSION));
            return;
        }

        if (p.getGameMode() == GameMode.CREATIVE) {
            e.setCancelled(true);
            refreshClientBlock(block);
            p.sendActionBar(Component.text("В CREATIVE добыча узлов отключена."));
            return;
        }

        // Добыча только алмазной / незеритовой киркой
        if (!isAllowedPickaxe(p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            refreshClientBlock(block);
            p.sendActionBar(Component.text("Нужна алмазная или незеритовая кирка."));
            return;
        }

        e.setCancelled(true);
        refreshClientBlock(block);
        startOrUpdateHold(p, block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockDamage(BlockDamageEvent e) {
        Block block = e.getBlock();
        if (!nm.isNode(block.getLocation())) return;

        Player p = e.getPlayer();
        if (!p.hasPermission(USE_PERMISSION) || p.getGameMode() == GameMode.CREATIVE) {
            e.setCancelled(true);
            e.setInstaBreak(false);
            refreshClientBlock(block);
            return;
        }

        // Добыча только алмазной / незеритовой киркой
        if (!isAllowedPickaxe(p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            e.setInstaBreak(false);
            refreshClientBlock(block);
            p.sendActionBar(Component.text("Нужна алмазная или незеритовая кирка."));
            return;
        }

        e.setCancelled(true);
        e.setInstaBreak(false);
        refreshClientBlock(block);
        startOrUpdateHold(p, block.getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        HoldSession hs = holds.get(p.getUniqueId());
        if (hs == null) return;

        Block target = p.getTargetBlockExact(6);
        if (target != null && sameBlock(hs.loc, target.getLocation())) {
            hs.lastSeenNs = System.nanoTime();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (!nm.isNode(block.getLocation())) return;

        e.setCancelled(true);
        refreshClientBlock(block);

        Player p = e.getPlayer();
        if (!p.hasPermission(USE_PERMISSION)) {
            p.sendActionBar(Component.text("Нет прав: " + USE_PERMISSION));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        holds.remove(e.getPlayer().getUniqueId());
    }

    private void tickHolds() {
        if (holds.isEmpty()) return;

        long now = System.nanoTime();
        long timeoutMs = plugin.getConfig().getLong("performance.hold-timeout-ms", 400L);
        long timeoutNs = timeoutMs * 1_000_000L;
        int budget = Math.max(1, plugin.getConfig().getInt("performance.max-hold-hits-per-tick", 60));
        int processed = 0;

        Iterator<Map.Entry<UUID, HoldSession>> it = holds.entrySet().iterator();
        while (it.hasNext()) {
            if (processed >= budget) break;

            Map.Entry<UUID, HoldSession> e = it.next();
            UUID uuid = e.getKey();
            HoldSession hs = e.getValue();

            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) { it.remove(); continue; }

            if (!p.hasPermission(USE_PERMISSION) || p.getGameMode() == GameMode.CREATIVE) {
                it.remove();
                continue;
            }

            // Если игрок сменил инструмент во время удержания — прекращаем.
            if (!isAllowedPickaxe(p.getInventory().getItemInMainHand())) {
                it.remove();
                p.sendActionBar(Component.text("Нужна алмазная или незеритовая кирка."));
                continue;
            }

            if (now - hs.lastSeenNs > timeoutNs) {
                it.remove();
                continue;
            }

            // Не вызываем hs.loc.getChunk(): он может загрузить чанк. Проверяем безопасно.
            World w = hs.loc.getWorld();
            if (!nm.isNode(hs.loc) || w == null || !w.isChunkLoaded(hs.loc.getBlockX() >> 4, hs.loc.getBlockZ() >> 4)) {
                it.remove();
                continue;
            }

            Block block = hs.loc.getBlock();
            NodeData nd = nm.getNode(hs.loc);
            if (nd == null) { it.remove(); continue; }

            handleHit(p, block, nd);
            processed++;
        }
    }

    private void startOrUpdateHold(Player p, Location loc) {
        holds.put(p.getUniqueId(), new HoldSession(loc, System.nanoTime()));
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getWorld() == b.getWorld()
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private void refreshClientBlock(Block block) {
        Bukkit.getScheduler().runTask(plugin, () -> block.getState().update(true, false));
    }

    private void handleHit(Player p, Block block, NodeData nd) {
        giveOreDrop(p, block, nd);

        nd.hitsRemaining--;

        if (nd.hitsRemaining <= 0) {
            // По запросу: после добычи узел НЕ должен обновляться (не ставим в очередь респавна)
            block.setType(Material.BEDROCK, false);

            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_ANVIL_PLACE, 0.7f, 0.8f);
            nm.removeNode(block.getLocation());
            p.sendActionBar(Component.text("Осталось ударов: 0/" + nd.maxHits));
        } else {
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_STONE_HIT, 0.8f, 1.0f);
            block.getWorld().spawnParticle(
                    Particle.BLOCK,
                    block.getLocation().add(0.5, 0.5, 0.5),
                    12, 0.3, 0.3, 0.3, 0.0,
                    block.getBlockData()
            );
            showProgress(p, block.getLocation(), nd);
        }
    }

    private void showProgress(Player p, Location loc, NodeData nd) {
        float progress = Math.min(0.98f, Math.max(0f, nd.progress()));
        try { p.sendBlockDamage(loc, progress); } catch (Throwable ignored) {}
        p.sendActionBar(Component.text("Осталось ударов: " + nd.hitsRemaining + "/" + nd.maxHits));
    }

    private void giveOreDrop(Player p, Block block, NodeData nd) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        int fortune = tool != null ? tool.getEnchantmentLevel(Enchantment.FORTUNE) : 0;
        boolean fortuneEnabled = plugin.getConfig().getBoolean("fortune-enabled", true);

        int amount = baseAmount(nd.oreMaterial);
        if (fortuneEnabled && fortune > 0) {
            amount *= (1 + rnd.nextInt(fortune + 1));
        }

        Material dropMat = dropFor(nd.oreMaterial);
        if (dropMat == null) return;

        ItemStack drop = new ItemStack(dropMat, Math.max(1, amount));

        boolean directInv = plugin.getConfig().getBoolean("performance.direct-item-to-inventory", true);
        if (directInv) {
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(drop);
            if (!leftover.isEmpty()) {
                for (ItemStack rest : leftover.values()) {
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), rest);
                }
            }
        } else {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        }

        int xp = xpFor(nd.oreMaterial);
        if (xp > 0) {
            String mode = plugin.getConfig().getString("performance.xp-mode", "direct");
            if ("direct".equalsIgnoreCase(mode)) {
                p.giveExp(xp);
            } else {
                ExperienceOrb orb = block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class);
                orb.setExperience(xp);
            }
        }
    }

    private int baseAmount(Material ore) {
        switch (ore) {
            case DEEPSLATE_REDSTONE_ORE: return 3 + rnd.nextInt(3);
            case DEEPSLATE_LAPIS_ORE:    return 3 + rnd.nextInt(3);
            case DEEPSLATE_COPPER_ORE:   return 1 + rnd.nextInt(2);
            case NETHERITE_SCRAP:        return 1;
            case ANCIENT_DEBRIS:         return 1;
            default: return 1;
        }
    }

    private int xpFor(Material ore) {
        switch (ore) {
            case DEEPSLATE_DIAMOND_ORE: return 3 + rnd.nextInt(5);
            case DEEPSLATE_EMERALD_ORE: return 3 + rnd.nextInt(5);
            case DEEPSLATE_REDSTONE_ORE: return 1 + rnd.nextInt(5);
            case DEEPSLATE_LAPIS_ORE: return 1 + rnd.nextInt(5);
            case DEEPSLATE_COAL_ORE: return rnd.nextInt(2);
            case NETHERITE_SCRAP: return 2 + rnd.nextInt(3);
            case ANCIENT_DEBRIS: return 2 + rnd.nextInt(3);
            default: return 0;
        }
    }

    private Material dropFor(Material ore) {
        switch (ore) {
            case DEEPSLATE_DIAMOND_ORE: return Material.DIAMOND;
            case DEEPSLATE_EMERALD_ORE: return Material.EMERALD;
            case DEEPSLATE_REDSTONE_ORE: return Material.REDSTONE;
            case DEEPSLATE_LAPIS_ORE: return Material.LAPIS_LAZULI;
            case DEEPSLATE_COAL_ORE: return Material.COAL;
            case DEEPSLATE_COPPER_ORE: return Material.RAW_COPPER;
            case DEEPSLATE_IRON_ORE: return Material.RAW_IRON;
            case DEEPSLATE_GOLD_ORE: return Material.RAW_GOLD;

            // Netherite:
            case NETHERITE_SCRAP: return Material.NETHERITE_SCRAP;
            // Backward compatibility: если кто-то оставит ANCIENT_DEBRIS в weights — тоже дропаем scrap
            case ANCIENT_DEBRIS: return Material.NETHERITE_SCRAP;

            default: return null;
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (nm.isNode(b.getLocation())) it.remove();
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (nm.isNode(b.getLocation())) it.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (nm.isNode(b.getLocation())) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (nm.isNode(b.getLocation())) { e.setCancelled(true); return; }
        }
    }
}
