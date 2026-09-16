package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BatchCpuAccountingTest {
    @Test
    void independentBatchWidthPreventsSquaringTheWholeCpuBudget() {
        assertEquals(65_536L, BatchCpuAccounting.maxCopiesForBatch(
                16_384, 256, Long.MAX_VALUE, BatchCpuAccounting.Mode.QUADRATIC));
        assertEquals(4_096L, BatchCpuAccounting.maxCopiesForBatch(
                64, 256, Long.MAX_VALUE, BatchCpuAccounting.Mode.QUADRATIC));
        assertEquals(1_000L, BatchCpuAccounting.maxCopiesForBatch(
                16_384, 256, 1_000L, BatchCpuAccounting.Mode.QUADRATIC));
    }
    @Test
    void quadraticBudgetContinuesPastIntegerCopyRange() {
        assertEquals(10_000_000_000L,
                BatchCpuAccounting.maxCopiesForCpuOps(100_000, BatchCpuAccounting.Mode.QUADRATIC));
    }

    @Test
    void quadraticCostHandlesTheEntireLongRangeWithoutOverflow() {
        assertEquals(Integer.MAX_VALUE,
                BatchCpuAccounting.cpuOpsForCopies(Long.MAX_VALUE, BatchCpuAccounting.Mode.QUADRATIC));
    }

    @Test
    void successfulDispatchModeUsesOneDispatchAndTheVirtualCpuCopyLimit() {
        assertEquals(4_194_304L, BatchCpuAccounting.maxCopiesForBatch(
                1,
                1,
                4_194_304L,
                BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH));
        assertEquals(1, BatchCpuAccounting.cpuOpsForCopies(
                4_194_304L,
                BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH));
        assertEquals(0L, BatchCpuAccounting.maxCopiesForBatch(
                0,
                1,
                4_194_304L,
                BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH));
    }
}
