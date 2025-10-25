package dev.dagxam.bedrockores.visual;

import dev.dagxam.bedrockores.node.NodeData;
import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

public class VisualOverlay {
    private static final String TAG = "bo_overlay";

    private final Plugin plugin;
    private final NodeManager nodeManager;

    private final Material overlayMat;
    private final float scale;
    private final boolean glow;
    private final int brightness; // 0..15

    public VisualOverlay(Plugin plugin, NodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;

        String matName = plugin.getConfig().getString("visual.overlay.material", "LIGHT_BLUE_STAINED_GLASS");
        Material m = Material.LIGHT_BLUE_STAINED_GLASS;
        try { m = Material.valueOf(matName); } catch (Exception ignored) {}
        this.overlayMat = m;

        this.scale = (float) plugin.getConfig().getDouble("visual.overlay.scale", 1.02);
        this.glow = plugin.getConfig().getBoolean("visual.overlay.glow", true);

        int br = plugin.getConfig().getInt("visual.overlay.brightness", 15);
        if (br < 0) br = 0;
        if (br > 15) br = 15;
        this.brightness = br;
    }

    // Вызвать один раз после загрузки узлов (в onEnable)
    public void syncAllFromNodes() {
        for (String key : nodeManager.nodeKeysSnapshot()) {
            Location loc = nodeManager.toLocation(key);
            if (loc == null) continue;
            if (!loc.getChunk().isLoaded()) continue;
            spawnOverlayIfMissing(loc);
        }
    }

    public void onNodeAdded(Location loc, NodeData nd) {
        if (!loc.getChunk().isLoaded()) return;
        spawnOverlayIfMissing(loc);
    }

    public void onNodeRemoved(Location loc) {
        removeOverlay(loc);
    }

    public void cleanup() {
        // Удаляем все наши оверлеи (по тегу) во всех загруженных мирах
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e instanceof BlockDisplay && e.getScoreboardTags().contains(TAG)) {
                    e.remove();
                }
            }
        }
    }

    private void spawnOverlayIfMissing(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        if (hasOverlayAt(loc)) return;

        Location at = loc.clone().add(0.5, 0.5, 0.5);
        w.spawn(at, BlockDisplay.class, d -> {
            d.addScoreboardTag(TAG);
            d.setPersistent(true);
            d.setInvulnerable(true);
            d.setBlock(overlayMat.createBlockData());
            if (glow) d.setGlowing(true);
            try {
                d.setBrightness(new Display.Brightness(brightness, brightness));
            } catch (Throwable ignored) {}

            try {
                Transformation tr = d.getTransformation();
                Vector3f s = new Vector3f(scale, scale, scale);
                d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), s, tr.getRightRotation()));
            } catch (Throwable ignored) {}
        });
    }

    private boolean hasOverlayAt(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        for (Entity e : w.getNearbyEntities(loc, 0.6, 0.6, 0.6)) {
            if (e instanceof BlockDisplay bd) {
                if (bd.getScoreboardTags().contains(TAG) && sameBlock(bd.getLocation(), loc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void removeOverlay(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        for (Entity e : w.getNearbyEntities(loc, 0.6, 0.6, 0.6)) {
            if (e instanceof BlockDisplay bd) {
                if (bd.getScoreboardTags().contains(TAG) && sameBlock(bd.getLocation(), loc)) {
                    bd.remove();
                }
            }
        }
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getWorld() == b.getWorld()
            && a.getBlockX() == b.getBlockX()
            && a.getBlockY() == b.getBlockY()
            && a.getBlockZ() == b.getBlockZ();
    }
}
