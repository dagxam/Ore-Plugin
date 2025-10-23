package dev.dagxam.bedrockores.node;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

public class OreListeners implements Listener {
    private final Plugin plugin;
    private final NodeManager nm;
    private final Random rnd = new Random();

    // Анти-дубль (защита от нескольких событий за один клик)
    private final Map<String, Long> lastHitAt = new HashMap<>();

    public OreListeners(Plugin plugin, NodeManager nm) {
        this.plugin = plugin;
        this.nm = nm;
    }

    // Первый перехват — левый клик по блоку
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeftClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (!nm.isNode(block.getLocation())) return;

        e.setCancelled(true);
        refreshClientBlock(block);
        if (!registerHitOnce(block)) return;

        NodeData nd = nm.getNode(block.getLocation());
        if (nd != null) {
            handleHit(e.getPlayer(), block, nd);
        }
    }

    // Доп. перехват — "повреждение" блока (progress bar)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockDamage(BlockDamageEvent e) {
        Block block = e.getBlock();
        if (!nm.isNode(block.getLocation())) return;

        e.setCancelled(true);
        e.setInstaBreak(false);
        refreshClientBlock(block);
        if (!registerHitOnce(block)) return;

        NodeData nd = nm.getNode(block.getLocation());
        if (nd != null) {
            handleHit(e.getPlayer(), block, nd);
        }
    }

    // Резерв — попытка слома (всегда запрещаем)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (!nm.isNode(block.getLocation())) return;

        e.setCancelled(true);
        refreshClientBlock(block);
        if (!registerHitOnce(block)) return;

        NodeData nd = nm.getNode(block.getLocation());
        if (nd != null) {
            handleHit(e.getPlayer(), block, nd);
        }
    }

    private boolean registerHitOnce(Block block) {
        String key = NodeManager.key(block.getLocation());
        long now = System.nanoTime();
        Long prev = lastHitAt.get(key);
        // 200 мс защита от повторных срабатываний для одного клика
        if (prev != null && (now - prev) < 200_000_000L) {
            return false;
        }
        lastHitAt.put(key, now);
        return true;
    }

    // Возвращаем клиенту корректное состояние блока на следующем тике
    private void refreshClientBlock(Block block) {
        Bukkit.getScheduler().runTask(plugin, () -> block.getState().update(true, false));
    }

    private void handleHit(Player p, Block block, NodeData nd) {
        // Дроп на каждый удар
        giveOreDrop(p, block, nd);

        // Считаем удар
        nd.hitsRemaining--;

        if (nd.hitsRemaining <= 0) {
            // Планируем респаун этой же руды через заданную задержку (по умолчанию 1 час)
            nm.scheduleRespawn(block.getLocation(), nd.oreMaterial);

            // Превращаемся в бедрок и снимаем узел
            block.setType(Material.BEDROCK, false);
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_ANVIL_PLACE, 0.7f, 0.8f);
            nm.removeNode(block.getLocation());

            p.sendActionBar(Component.text("Осталось ударов: 0/" + nd.maxHits));
        } else {
            // Эффекты и прогресс
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
        try {
            p.sendBlockDamage(loc, progress);
        } catch (Throwable ignored) {}
        p.sendActionBar(Component.text("Осталось ударов: " + nd.hitsRemaining + "/" + nd.maxHits));
    }

    private void giveOreDrop(Player p, Block block, NodeData nd) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        int fortune = tool != null ? tool.getEnchantmentLevel(Enchantment.FORTUNE) : 0;
        boolean fortuneEnabled = plugin.getConfig().getBoolean("fortune-enabled", true);

        int amount = baseAmount(nd.oreMaterial);
        if (fortuneEnabled && fortune > 0) {
            // Простая модель Fortune: умножаем на (1 + random(0..fortune))
            amount *= (1 + rnd.nextInt(fortune + 1));
        }

        Material dropMat = dropFor(nd.oreMaterial);
        if (dropMat == null) return;

        ItemStack drop = new ItemStack(dropMat, Math.max(1, amount));
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);

        int xp = xpFor(nd.oreMaterial);
        if (xp > 0) {
            ExperienceOrb orb = block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class);
            orb.setExperience(xp);
        }
    }

    private int baseAmount(Material ore) {
        switch (ore) {
            case DEEPSLATE_REDSTONE_ORE: return 3 + rnd.nextInt(3); // 3-5
            case DEEPSLATE_LAPIS_ORE:    return 3 + rnd.nextInt(3); // 3-5
            case DEEPSLATE_COPPER_ORE:   return 1 + rnd.nextInt(2); // 1-2
            default: return 1; // алмазы, изумруды, уголь, железо, золото
        }
    }

    private int xpFor(Material ore) {
        switch (ore) {
            case DEEPSLATE_DIAMOND_ORE: return 3 + rnd.nextInt(5); // 3-7
            case DEEPSLATE_EMERALD_ORE: return 3 + rnd.nextInt(5);
            case DEEPSLATE_REDSTONE_ORE: return 1 + rnd.nextInt(5);
            case DEEPSLATE_LAPIS_ORE: return 1 + rnd.nextInt(5);
            case DEEPSLATE_COAL_ORE: return rnd.nextInt(2); // 0-1
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
            default: return null;
        }
    }

    // Защита от взрывов
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

    // Защита от поршней
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (nm.isNode(b.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (nm.isNode(b.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
