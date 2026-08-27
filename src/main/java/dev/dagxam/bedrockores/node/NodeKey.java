package dev.dagxam.bedrockores.node;

import org.bukkit.Location;

import java.util.UUID;

/** Immutable block coordinate key without String parsing/allocation. */
public record NodeKey(UUID worldId, int x, int y, int z) {
    public static NodeKey of(Location loc) {
        return new NodeKey(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public long chunkKey() {
        return (((long) (x >> 4)) << 32) ^ ((z >> 4) & 0xffffffffL);
    }
}
