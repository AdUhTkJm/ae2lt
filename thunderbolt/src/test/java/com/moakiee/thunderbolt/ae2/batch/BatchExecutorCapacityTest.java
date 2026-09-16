package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BatchExecutorCapacityTest {
    @Test
    void zeroMeansUnavailableAndOneUsesOrdinaryDispatch() {
        assertFalse(BatchExecutor.usesBatchPath(0L));
        assertFalse(BatchExecutor.usesBatchPath(1L));
    }

    @Test
    void capacitiesAboveOneUseBatchDispatch() {
        assertTrue(BatchExecutor.usesBatchPath(2L));
        assertTrue(BatchExecutor.usesBatchPath(Long.MAX_VALUE));
    }
}
