package dev.dagxam.bedrockores.node;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import java.util.Random;

/** One-hit rich ore nodes. Drop amount is configurable per ore. */
public class OreListeners implements Listener {
 private static final String USE_PERMISSION="bedrockores.use"; private final Plugin plugin; private final NodeManager nm; private final Random random=new Random();
 public OreListeners(Plugin plugin,NodeManager nm){this.plugin=plugin;this.nm=nm;}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void onBreak(BlockBreakEvent event){Block block=event.getBlock();if(!nm.isNode(block.getLocation()))return;event.setCancelled(true);Player player=event.getPlayer();if(!canMine(player))return;NodeData data=nm.getNode(block.getLocation());if(data!=null)breakNode(player,block,data);}
 private boolean canMine(Player player){if(!player.hasPermission(USE_PERMISSION)){player.sendActionBar(net.kyori.adventure.text.Component.text("Нет прав: "+USE_PERMISSION));return false;}if(player.getGameMode()==GameMode.CREATIVE)return false;Material tool=player.getInventory().getItemInMainHand().getType();return tool==Material.DIAMOND_PICKAXE||tool==Material.NETHERITE_PICKAXE;}
 private int cfg(String ore,String key,int def){return Math.max(0,plugin.getConfig().getInt("rich-ores."+ore+"."+key,def));}
 private void breakNode(Player player,Block block,NodeData data){String ore=data.oreMaterial.name();int min=Math.max(1,cfg(ore,"drops-min",plugin.getConfig().getInt("drops.min-per-node",5))),max=Math.max(min,cfg(ore,"drops-max",plugin.getConfig().getInt("drops.max-per-node",20)));int amount=min+random.nextInt(max-min+1);Material drop=dropFor(data.oreMaterial);if(drop!=null)give(player,block,new ItemStack(drop,amount));int xp=Math.max(1,amount/2);if("orb".equalsIgnoreCase(plugin.getConfig().getString("performance.xp-mode","direct"))){ExperienceOrb orb=block.getWorld().spawn(block.getLocation().add(.5,.5,.5),ExperienceOrb.class);orb.setExperience(xp);}else player.giveExp(xp);Material restore=restoreMaterial(block.getWorld(),block.getY());nm.removeNode(block.getLocation());block.setType(restore,false);nm.scheduleRespawn(block.getLocation(),data.oreMaterial);block.getWorld().playSound(block.getLocation().add(.5,.5,.5),Sound.BLOCK_STONE_BREAK,1f,1f);block.getWorld().spawnParticle(Particle.BLOCK,block.getLocation().add(.5,.5,.5),20,.35,.35,.35,0,block.getBlockData());}
 private Material restoreMaterial(World world,int y){return switch(world.getEnvironment()){case NETHER->Material.NETHERRACK;case THE_END->Material.END_STONE;default->y<0?Material.DEEPSLATE:Material.STONE;};}
 private void give(Player player,Block block,ItemStack item){if(plugin.getConfig().getBoolean("performance.direct-item-to-inventory",true)){for(ItemStack rest:player.getInventory().addItem(item).values())block.getWorld().dropItemNaturally(block.getLocation().add(.5,.5,.5),rest);}else block.getWorld().dropItemNaturally(block.getLocation().add(.5,.5,.5),item);}
 private Material dropFor(Material ore){return switch(ore){case DEEPSLATE_DIAMOND_ORE,DIAMOND_ORE->Material.DIAMOND;case DEEPSLATE_EMERALD_ORE,EMERALD_ORE->Material.EMERALD;case DEEPSLATE_REDSTONE_ORE,REDSTONE_ORE->Material.REDSTONE;case DEEPSLATE_LAPIS_ORE,LAPIS_ORE->Material.LAPIS_LAZULI;case DEEPSLATE_COAL_ORE,COAL_ORE->Material.COAL;case DEEPSLATE_COPPER_ORE,COPPER_ORE->Material.RAW_COPPER;case DEEPSLATE_IRON_ORE,IRON_ORE->Material.RAW_IRON;case DEEPSLATE_GOLD_ORE,GOLD_ORE->Material.RAW_GOLD;case NETHER_GOLD_ORE->Material.GOLD_NUGGET;case NETHER_QUARTZ_ORE->Material.QUARTZ;case ANCIENT_DEBRIS,NETHERITE_SCRAP->Material.NETHERITE_SCRAP;default->null;};}
 @EventHandler public void onEntityExplode(EntityExplodeEvent e){e.blockList().removeIf(b->nm.isNode(b.getLocation()));}@EventHandler public void onBlockExplode(BlockExplodeEvent e){e.blockList().removeIf(b->nm.isNode(b.getLocation()));}@EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void onPistonExtend(BlockPistonExtendEvent e){for(Block b:e.getBlocks())if(nm.isNode(b.getLocation())){e.setCancelled(true);return;}}@EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void onPistonRetract(BlockPistonRetractEvent e){for(Block b:e.getBlocks())if(nm.isNode(b.getLocation())){e.setCancelled(true);return;}}
}
