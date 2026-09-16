package com.moakiee.thunderbolt.core.crafting.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CraftingProviderChangeTrackerTest {
    @Test
    void ignoresStableProviderStateAfterItHasBeenObserved() {
        var tracker = new CraftingProviderChangeTracker();

        assertTrue(tracker.shouldRecheck(8L, 10L));
        assertFalse(tracker.shouldRecheck(8L, 11L));
    }

    @Test
    void rechecksOnceAfterAnEqualTickToCatchLateSameTickChanges() {
        var tracker = new CraftingProviderChangeTracker();

        assertTrue(tracker.shouldRecheck(10L, 10L));
        assertTrue(tracker.shouldRecheck(10L, 11L));
        assertFalse(tracker.shouldRecheck(10L, 12L));
    }

    @Test
    void detectsLaterChangesAndCanBeResetForANewGrid() {
        var tracker = new CraftingProviderChangeTracker();

        assertTrue(tracker.shouldRecheck(5L, 8L));
        assertTrue(tracker.shouldRecheck(12L, 12L));
        tracker.reset();
        assertTrue(tracker.shouldRecheck(1L, 20L));
    }
}
