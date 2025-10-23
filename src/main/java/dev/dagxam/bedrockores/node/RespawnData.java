package dev.dagxam.bedrockores.node;

import org.bukkit.Material;

public class RespawnData {
    public final Material oreMaterial;
    public final long dueAtMillis;

    public RespawnData(Material oreMaterial, long dueAtMillis) {
        this.oreMaterial = oreMaterial;
        this.dueAtMillis = dueAtMillis;
    }
}
