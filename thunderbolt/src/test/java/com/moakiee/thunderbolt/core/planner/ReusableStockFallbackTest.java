package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReusableStockFallbackTest {
    @Test
    void dependencyStockIsNotPublishedTwiceAsSelfSeedStock() {
        assertEquals(0L, ReusableStockFallback.supplementalSelfSeedStock(1L, 1L, 1L));
    }

    @Test
    void requestedOutputStockHiddenByAe2IsPublishedOnlyForItsSeed() {
        assertEquals(1L, ReusableStockFallback.supplementalSelfSeedStock(1L, 1L, 0L));
    }

    @Test
    void supplementalSeedStockIsBoundedByRequirementAndAvailability() {
        assertEquals(2L, ReusableStockFallback.supplementalSelfSeedStock(3L, 2L, 0L));
        assertEquals(1L, ReusableStockFallback.supplementalSelfSeedStock(3L, 2L, 1L));
        assertEquals(0L, ReusableStockFallback.supplementalSelfSeedStock(0L, 2L, 0L));
    }
}
