package dev.dagxam.bedrockores.node;

import org.bukkit.Material;

/**
 * Runtime state of an ore node.
 *
 * The generator itself is responsible for deciding where a node should exist.
 * NodeData only stores mutable gameplay state for an existing node.
 */
public final class NodeData {

    public final Material oreMaterial;
    public final int maxHits;

    public int hitsRemaining;

    public NodeData(Material oreMaterial, int hitsRemaining, int maxHits) {
        if (oreMaterial == null) {
            throw new IllegalArgumentException("oreMaterial cannot be null");
        }

        this.oreMaterial = oreMaterial;
        this.maxHits = Math.max(1, maxHits);
        this.hitsRemaining = Math.max(0, Math.min(hitsRemaining, this.maxHits));
    }

    /**
     * Mining progress in the inclusive range [0, 1].
     */
    public float progress() {
        return 1.0f - (hitsRemaining / (float) maxHits);
    }

    /**
     * Returns true when the node has no hits left.
     */
    public boolean depleted() {
        return hitsRemaining <= 0;
    }
}
