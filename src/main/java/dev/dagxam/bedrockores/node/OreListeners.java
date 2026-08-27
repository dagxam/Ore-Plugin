package dev.dagxam.bedrockores.node;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
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
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class OreListeners implements Listener {
    private static final String USE_PERMISSION="bedrockores.use";
    private final Plugin plugin; private final NodeManager nm; private final Random random=new Random();
    private final Map<UUID,HoldSession> holds=new HashMap<>(); private BukkitTask holdTask;
    private static final class HoldSession { final Location loc; long lastSeenNs; HoldSession(Location l,long n){loc=l;lastSeenNs=n;} }
    public OreListeners(Plugin plugin,NodeManager nm){this.plugin=plugin;this.nm=nm;int interval=Math.max(1,plugin.getConfig().getInt("performance.hold-hit-interval-ticks",5));holdTask=Bukkit.getScheduler().runTaskTimer(plugin,this::tickHolds,interval,interval);}
    private boolean allowed(ItemStack tool){if(tool==null)return false;return tool.getType()==Material.DIAMOND_PICKAXE||tool.getType()==Material.NETHERITE_PICKAXE;}
    private boolean canMine(Player p,Block b,boolean message){if(!p.hasPermission(USE_PERMISSION)){if(message)p.sendActionBar(Component.text("Нет прав: "+USE_PERMISSION));return false;}if(p.getGameMode()==GameMode.CREATIVE){if(message)p.sendActionBar(Component.text("В CREATIVE добыча узлов отключена."));return false;}if(!allowed(p.getInventory().getItemInMainHand())){if(message)p.sendActionBar(Component.text("Нужна алмазная или незеритовая кирка."));return false;}return true;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void onLeftClick(PlayerInteractEvent e){if(e.getAction()!=Action.LEFT_CLICK_BLOCK||e.getHand()!=EquipmentSlot.HAND)return;Block b=e.getClickedBlock();if(b==null||!nm.isNode(b.getLocation()))return;e.setCancelled(true);refresh(b);if(canMine(e.getPlayer(),b,true))start(e.getPlayer(),b.getLocation());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void onDamage(BlockDamageEvent e){Block b=e.getBlock();if(!nm.isNode(b.getLocation()))return;e.setCancelled(true);e.setInstaBreak(false);refresh(b);if(canMine(e.getPlayer(),b,true))start(e.getPlayer(),b.getLocation());}
    @EventHandler(ignoreCancelled=true) public void onSwing(PlayerAnimationEvent e){HoldSession s=holds.get(e.getPlayer().getUniqueId());if(s==null)return;Block target=e.getPlayer().getTargetBlockExact(6);if(target!=null&&same(s.loc,target.getLocation()))s.lastSeenNs=System.nanoTime();}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void onBreak(BlockBreakEvent e){if(!nm.isNode(e.getBlock().getLocation()))return;e.setCancelled(true);refresh(e.getBlock());canMine(e.getPlayer(),e.getBlock(),true);}
    @EventHandler public void onQuit(PlayerQuitEvent e){holds.remove(e.getPlayer().getUniqueId());}
    private void start(Player p,Location l){holds.put(p.getUniqueId(),new HoldSession(l,System.nanoTime()));}
    private void tickHolds(){if(holds.isEmpty())return;long now=System.nanoTime(),timeout=Math.max(100L,plugin.getConfig().getLong("performance.hold-timeout-ms",400L))*1_000_000L;int budget=Math.max(1,plugin.getConfig().getInt("performance.max-hold-hits-per-tick",60)),done=0;Iterator<Map.Entry<UUID,HoldSession>> it=holds.entrySet().iterator();while(it.hasNext()&&done<budget){var e=it.next();Player p=Bukkit.getPlayer(e.getKey());HoldSession s=e.getValue();if(p==null||!p.isOnline()||now-s.lastSeenNs>timeout||!canMine(p,null,false)){it.remove();continue;}World w=s.loc.getWorld();if(w==null||!w.isChunkLoaded(s.loc.getBlockX()>>4,s.loc.getBlockZ()>>4)||!nm.isNode(s.loc)){it.remove();continue;}NodeData data=nm.getNode(s.loc);if(data==null){it.remove();continue;}handleHit(p,s.loc.getBlock(),data);done++;}}
    private boolean same(Location a,Location b){return a.getWorld()==b.getWorld()&&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();}
    private void refresh(Block b){Bukkit.getScheduler().runTask(plugin,()->b.getState().update(true,false));}
    private void handleHit(Player p,Block b,NodeData d){giveDrop(p,b,d);if(--d.hitsRemaining<=0){nm.removeNode(b.getLocation());b.setType(Material.BEDROCK,false);p.sendBlockDamage(b.getLocation(),0f);b.getWorld().playSound(b.getLocation().add(.5,.5,.5),Sound.BLOCK_ANVIL_PLACE,.7f,.8f);p.sendActionBar(Component.text("Осталось ударов: 0/"+d.maxHits));holds.remove(p.getUniqueId());}else{b.getWorld().playSound(b.getLocation().add(.5,.5,.5),Sound.BLOCK_STONE_HIT,.8f,1f);b.getWorld().spawnParticle(Particle.BLOCK,b.getLocation().add(.5,.5,.5),12,.3,.3,.3,0,b.getBlockData());showProgress(p,b.getLocation(),d);}}
    private void showProgress(Player p,Location l,NodeData d){try{p.sendBlockDamage(l,Math.min(.98f,Math.max(0f,d.progress())));}catch(Throwable ignored){}p.sendActionBar(Component.text("Осталось ударов: "+d.hitsRemaining+"/"+d.maxHits));}
    private void giveDrop(Player p,Block b,NodeData d){ItemStack tool=p.getInventory().getItemInMainHand();if(plugin.getConfig().getBoolean("drops.respect-silk-touch",false)&&tool!=null&&tool.containsEnchantment(Enchantment.SILK_TOUCH)){give(p,b,new ItemStack(d.oreMaterial==Material.NETHERITE_SCRAP?Material.ANCIENT_DEBRIS:d.oreMaterial));return;}int fortune=tool==null?0:tool.getEnchantmentLevel(Enchantment.FORTUNE);int amount=base(d.oreMaterial);if(plugin.getConfig().getBoolean("fortune-enabled",true)&&fortune>0)amount=fortune(amount,fortune);Material drop=dropFor(d.oreMaterial);if(drop==null)return;give(p,b,new ItemStack(drop,Math.max(1,amount)));int xp=xpFor(d.oreMaterial);if(xp>0){if("orb".equalsIgnoreCase(plugin.getConfig().getString("performance.xp-mode","direct"))){ExperienceOrb orb=b.getWorld().spawn(b.getLocation().add(.5,.5,.5),ExperienceOrb.class);orb.setExperience(xp);}else p.giveExp(xp);}}
    private void give(Player p,Block b,ItemStack item){if(plugin.getConfig().getBoolean("performance.direct-item-to-inventory",true)){for(ItemStack rest:p.getInventory().addItem(item).values())b.getWorld().dropItemNaturally(b.getLocation().add(.5,.5,.5),rest);}else b.getWorld().dropItemNaturally(b.getLocation().add(.5,.5,.5),item);}
    private int fortune(int amount,int level){String mode=plugin.getConfig().getString("fortune.mode","vanilla");if("simple".equalsIgnoreCase(mode))return amount*(1+random.nextInt(level+1));int bonus=random.nextInt(level+2)-1;return bonus<0?amount:amount*(bonus+1);}
    private int base(Material o){return switch(o){case DEEPSLATE_REDSTONE_ORE,DEEPSLATE_LAPIS_ORE->4+random.nextInt(3);case DEEPSLATE_COPPER_ORE->2+random.nextInt(3);default->1;};}
    private int xpFor(Material o){return switch(o){case DEEPSLATE_DIAMOND_ORE,DEEPSLATE_EMERALD_ORE->3+random.nextInt(5);case DEEPSLATE_REDSTONE_ORE,DEEPSLATE_LAPIS_ORE->1+random.nextInt(5);case DEEPSLATE_COAL_ORE->random.nextInt(3);case NETHERITE_SCRAP,ANCIENT_DEBRIS->2+random.nextInt(3);default->0;};}
    private Material dropFor(Material o){return switch(o){case DEEPSLATE_DIAMOND_ORE->Material.DIAMOND;case DEEPSLATE_EMERALD_ORE->Material.EMERALD;case DEEPSLATE_REDSTONE_ORE->Material.REDSTONE;case DEEPSLATE_LAPIS_ORE->Material.LAPIS_LAZULI;case DEEPSLATE_COAL_ORE->Material.COAL;case DEEPSLATE_COPPER_ORE->Material.RAW_COPPER;case DEEPSLATE_IRON_ORE->Material.RAW_IRON;case DEEPSLATE_GOLD_ORE->Material.RAW_GOLD;case NETHERITE_SCRAP,ANCIENT_DEBRIS->Material.NETHERITE_SCRAP;default->null;};}
    @EventHandler public void onEntityExplode(EntityExplodeEvent e){e.blockList().removeIf(b->nm.isNode(b.getLocation()));}
    @EventHandler public void onBlockExplode(BlockExplodeEvent e){e.blockList().removeIf(b->nm.isNode(b.getLocation()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onPistonExtend(BlockPistonExtendEvent e){for(Block b:e.getBlocks())if(nm.isNode(b.getLocation())){e.setCancelled(true);return;}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onPistonRetract(BlockPistonRetractEvent e){for(Block b:e.getBlocks())if(nm.isNode(b.getLocation())){e.setCancelled(true);return;}}
}
