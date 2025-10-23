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

    // анти-дубль срабатывания на один и тот же клик
    private final Map<String, Long> lastHitAt = new HashMap<>();

    public OreListeners(Plugin plugin, NodeManager nm) {
        this.plugin = plugin;
        this.nm = nm;
    }

    // Перехватываем самый первый клик по блоку
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

    // Доп. защита — если началось "повреждение блока"
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

    // Резерв на случай, если все же дошло до попытки слома
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
        // 200 мс защита от повторов (один клик может дать Interact/Damage/Break)
        if (prev != null && (now - prev) < 200_000_000L) {
            return false;
        }
        lastHitAt.put(key, now);
        return true;
    }

    // Насильно "откатываем" клиенту вид блока (исправляем призрачное ломание)
    private void refreshClientBlock(Block block) {
        Bukkit.getScheduler().runTask(plugin, () -> block.getState().update(true, false));
    }

    private void handleHit(Player p, Block block, NodeData nd) {
        // Дроп за удар
        giveOreDrop(p, block, nd);

        // Считаем удар
        nd.hitsRemaining--;

        if (nd.hitsRemaining <= 0) {
            // Финал — превращаемся в бедрок
            block.setType(Material.BEDROCK, false);
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_ANVIL_PLACE, 0.7f, 0.8f);
            nm.removeNode(block.getLocation());
            p.sendActionBar(Component.text("Осталось ударов: 0/" + nd.maxHits));
        } else {
            // Звук, частицы, прогресс трещин
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
            amount *= (1 + rnd.nextInt(fortune + 
