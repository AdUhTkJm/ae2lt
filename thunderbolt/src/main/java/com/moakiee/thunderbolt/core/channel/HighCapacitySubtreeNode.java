package com.moakiee.thunderbolt.core.channel;

/**
 * Duck interface mixed into {@code GridNode} at runtime.
 * Marks whether this node belongs to an high-capacity source subtree
 * in the BFS routing tree, and exposes a channel-count setter for
 * max-flow-based channel assignment.
 */
public interface HighCapacitySubtreeNode {
    boolean thunderbolt$isInHighCapacitySubtree();

    void thunderbolt$setUsedChannels(int channels);
}
