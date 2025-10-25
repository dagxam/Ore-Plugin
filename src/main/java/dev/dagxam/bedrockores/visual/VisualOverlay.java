package dev.dagxam.bedrockores.visual;

import dev.dagxam.bedrockores.node.NodeData;
import dev.dagxam.bedrockores.node.NodeManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Scoreboard;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.Collection;

public class VisualOverlay {
    private static final String TAG = "bo_overlay";
    private static final String TEAM = "bedrockores_overlay";

    private final Plugin plugin;
    private final NodeManager nodeManager;

    private final Material overlayMat;
    private final float scale;
    private final boolean glow;
    private final ChatColor teamColor;
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
        this.teamColor = parseColor(plugin.getConfig().getString("visual.overlay.team-color", "AQUA"));
        int br = plugin.getConfig().getInt("visual.overlay.brightness", 15);
        if (br < 0) br = 0; if (br > 15) br = 15;
        this.brightness = br;

        ensureTeam();
    }

    public void syncAllFromNodes() {
        // для всех узлов — гарантируем наличие оверлея (только для загруженных чанков)
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
        // Удаляем все наши оверлеи (с тегом) во всех мирах
        for (World w : plugin.getServer().getWorlds()) {
            w.getEntitiesByClass(BlockDisplay.class).stream()
                .filter(e -> e.getScoreboardTags().contains(TAG))
                .forEach(Entity::remove);
        }
    }

    private void spawnOverlayIfMissing(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;

        boolean exists = w.getNearbyEntitiesByClass(BlockDisplay.class, loc, 0.6, e ->
                e.getScoreboardTags().contains(TAG) && sameBlock(e.getLocation(), loc)).findAny().isPresent();
        if (exists) return;

        // Спавним оверлей
        Location at = loc.clone().add(0.5, 0.5, 0.5);
        BlockDisplay disp = w.spawn(at, BlockDisplay.class, d -> {
            d.addScoreboardTag(TAG);
            d.setPersistent(true);
            d.setInvulnerable(true);
            d.setBlock(overlayMat.createBlockData());
            if (glow) {
                d.setGlowing(true);
                addToTeam(d, TEAM, teamColor);
            }
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

    private void removeOverlay(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        Collection<BlockDisplay> list = w.getNearbyEntitiesByClass(BlockDisplay.class, loc, 0.6, e ->
                e.getScoreboardTags().contains(TAG) && sameBlock(e.getLocation(), loc)).toList();
        for (BlockDisplay bd : list) bd.remove();
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getWorld() == b.getWorld()
            && a.getBlockX() == b.getBlockX()
            && a.getBlockY() == b.getBlockY()
            && a.getBlockZ() == b.getBlockZ();
    }

    private void ensureTeam() {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            Team t = sb.getTeam(TEAM);
            if (t == null) t = sb.registerNewTeam(TEAM);
            t.setColor(teamColor);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create overlay team: " + e.getMessage());
        }
    }

    private void addToTeam(Entity e, String team, ChatColor color) {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            Team t = sb.getTeam(team);
            if (t == null) {
                t = sb.registerNewTeam(team);
                t.setColor(color);
            }
            t.addEntry(e.getUniqueId().toString());
        } catch (Exception ignored) {}
    }

    private ChatColor parseColor(String name) {
        try { return ChatColor.valueOf(name.toUpperCase()); }
        catch (Exception e) { return ChatColor.AQUA; }
    }
}
