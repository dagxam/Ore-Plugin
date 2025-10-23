package dev.dagxam.bedrockores.node;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockBreakEvent;
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

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        Location loc = block.getLocation();

        if (!nm.isNode(loc)) return;

        // Блок "не ломаем"
        e.setCancelled(true);

        NodeData nd = nm.getNode(loc);
        Player p = e.getPlayer();

        // Дроп за "удар"
        giveOreDrop(p, block, nd);

        // Эффекты
        block.getWorld().playSound(loc.add(0.5, 0.5, 0.5), Sound.BLOCK_STONE_HIT, 0.8f, 1.0f);
        showProgress(p, loc, nd);

        // Снижаем счетчик
        nd.hitsRemaining--;
        if (nd.hitsRemaining <= 0) {
            // Превращаемся в бедрок
            block.setType(Material.BEDROCK, false);
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_ANVIL_PLACE, 0.7f, 0.8f);
            nm.removeNode(loc);
        } else {
            // небольшая пыль блока
            block.getWorld().spawnParticle(Particle.BLOCK_CRACK, block.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, block.getBlockData());
        }
    }

    private void showProgress(Player p, Location loc, NodeData nd) {
        float progress = Math.min(0.98f, Math.max(0f, nd.progress()));
        // Покажем анимацию трещин игроку
        try {
            p.sendBlockDamage(loc, progress);
        } catch (Throwable ignored) {}
        // Action bar (через Spigot)
        try {
            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent("Осталось ударов: " + nd.hitsRemaining + "/" + nd.maxHits));
        } catch (Throwable ignored) {}
    }

    private void giveOreDrop(Player p, Block block, NodeData nd) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        int fortune = tool != null ? tool.getEnchantmentLevel(Enchantment.LOOT_BONUS_BLOCKS) : 0;
        boolean fortuneEnabled = plugin.getConfig().getBoolean("fortune-enabled", true);
        int amount = baseAmount(nd.oreMaterial);
        if (fortuneEnabled && fortune > 0) {
            // простая модель Fortune: умножаем на (1 + random(0..fortune))
            amount *= (1 + rnd.nextInt(fortune + 1));
        }

        Material dropMat = dropFor(nd.oreMaterial);
        if (dropMat == null) return;

        ItemStack drop = new ItemStack(dropMat, Math.max(1, amount));
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);

        // Немного опыта, как в ваниле
        int xp = xpFor(nd.oreMaterial);
        if (xp > 0) {
            ExperienceOrb orb = block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class);
            orb.setExperience(xp);
        }
    }

    private int baseAmount(Material ore) {
        // базовое количество добычи за 1 "удар"
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
            default: return 0; // железо/медь/золото - не дают опыт при сырой руде
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
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (nm.isNode(b.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (nm.isNode(b.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
