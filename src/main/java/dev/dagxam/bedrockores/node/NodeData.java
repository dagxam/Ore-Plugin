package dev.dagxam.bedrockores.node;

import org.bukkit.Material;

public class NodeData {
    public final Material oreMaterial; // тип руды (DEEPSLATE_*_ORE)
    public int hitsRemaining;
    public final int maxHits;

    public NodeData(Material oreMaterial, int hitsRemaining, int maxHits) {
        this.oreMaterial = oreMaterial;
        this.hitsRemaining = hitsRemaining;
        this.maxHits = maxHits;
    }

    public float progress() {
        return 1.0f - (hitsRemaining / (float) maxHits);
    }
}
