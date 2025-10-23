package dev.dagxam.bedrockores.node;

import net.kyori.adventure.text.Component;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.Random;

public class OreListeners implements Listener {
    private final Plugin plugin;
    private final NodeManager nm;
    private final Random rnd = new Random();

    public OreListeners(Plugin plugin, NodeManager nm) {
        this.plugin = plugin;
        this.nm = nm;
    }

    // Основной "удар" — по клику (BlockDamage), чтобы блок не успевал ломаться
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockDamage(BlockDamageEvent e) {
        Block block = e.getBlock();
        if (!nm.isNode(block.getLocation())) return;

        // Полностью блокируем стандартное повреждение блока
        e.setCancelled(true);
        e.setInstaBreak(false);

        NodeData nd = nm.getNode(block.getLocation());
        if (nd != null) {
            handleHit(e.getPlayer(), block, nd);
        }
    }

    // Резерв: если всё-таки дошло до попытки сломать блок — считаем как "удар", но запрещаем ломание
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (!nm.isNode(block.getLocation())) return;

        e.setCancelled(true);
        NodeData nd = nm.getNode(block.getLocation());
        if (nd != null) {
            handleHit(e.getPlayer(), block, nd);
        }
    }

    private void handleHit(Player p, Block block, NodeData nd) {
        // Дроп за удар
        giveOreDrop(p, block, nd);

        // Звук и прогресс (после выдачи дропа уменьшим оставшиеся "удары")
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_STONE_HIT, 0.8f, 1.0f);

        // Считаем удар
        nd.hitsRemaining--;

        if (nd.hitsRemaining <= 0) {
            // Финал — превращаемся в бедрок
            block.setType(Material.BEDROCK, false);
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_ANVIL_PLACE, 0.7f, 0.8f);
            nm.removeNode(block.getLocation());
            // Обновим action bar, что всё — 0/...
            p.sendActionBar(Component.text("Осталось ударов: 0/" + nd.maxHits));
        } else {
            // Частицы блока и прогресс трещин
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
            case DEEPSLATE_REDSTONE_ORE: return 3 + rnd.nextInt(3);
            case DEEPSLATE_LAPIS_ORE:    return 3 + rnd.nextInt(3);
            case DEEPSLATE_COPPER_ORE:   return 1 + rnd.nextInt(2);
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
