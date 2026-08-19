package dev.dagxam.bedrockores.node;

import org.bukkit.Material;

/**
 * Persistent respawn information for a depleted ore node.
 */
public final class RespawnData {

    public final Material oreMaterial;
    public final long dueAtMillis;

    public RespawnData(Material oreMaterial, long dueAtMillis) {
        if (oreMaterial == null) {
            throw new IllegalArgumentException("oreMaterial cannot be null");
        }

        this.oreMaterial = oreMaterial;
        this.dueAtMillis = Math.max(0L, dueAtMillis);
    }

    public boolean isDue(long nowMillis) {
        return dueAtMillis <= nowMillis;
    }
}
