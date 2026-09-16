package com.moakiee.thunderbolt.core.crafting.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CraftingCpuSelectionOrderTest {
    @Test
    void prioritizePowerSelectsMoreCoProcessorsAcrossCpuFamilies() {
        assertTrue(compare(0, false, 8, 4096, 0, false, 2, 1024, true) < 0);
        assertTrue(compare(0, false, 2, 1024, 0, false, 8, 4096, true) > 0);
    }

    @Test
    void normalOrderingSelectsFewerCoProcessorsAcrossCpuFamilies() {
        assertTrue(compare(0, false, 2, 4096, 0, false, 8, 1024, false) < 0);
        assertTrue(compare(0, false, 8, 1024, 0, false, 2, 4096, false) > 0);
    }

    @Test
    void preferredSourceOutranksPowerAndStorage() {
        assertTrue(compare(0, true, 0, Long.MAX_VALUE,
                0, false, Integer.MAX_VALUE, 1, true) < 0);
        assertTrue(compare(0, true, Integer.MAX_VALUE, Long.MAX_VALUE,
                0, false, 0, 1, false) < 0);
    }

    @Test
    void explicitCpuPriorityOutranksSourcePreferencePowerAndStorage() {
        assertTrue(compare(100, false, 0, 1,
                0, true, Integer.MAX_VALUE, Long.MAX_VALUE, true) < 0);
        assertTrue(compare(-1, true, Integer.MAX_VALUE, Long.MAX_VALUE,
                0, false, 0, 1, false) > 0);
    }

    @Test
    void storageBreaksEqualPowerTiesAndExactTiesStayStable() {
        assertTrue(compare(0, false, 4, 1024, 0, false, 4, 4096, true) < 0);
        assertEquals(0, compare(0, false, 4, 1024, 0, false, 4, 1024, true));
    }

    private static int compare(
            int firstPriority,
            boolean firstPreferred,
            int firstCoProcessors,
            long firstAvailableStorage,
            int secondPriority,
            boolean secondPreferred,
            int secondCoProcessors,
            long secondAvailableStorage,
            boolean prioritizePower) {
        return CraftingCpuSelectionOrder.compare(
                firstPriority,
                firstPreferred,
                firstCoProcessors,
                firstAvailableStorage,
                secondPriority,
                secondPreferred,
                secondCoProcessors,
                secondAvailableStorage,
                prioritizePower);
    }
}
