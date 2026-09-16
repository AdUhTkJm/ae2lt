package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BatchExecutorRuleRegistrationTest {
    @Test
    void registrationsComposeInsteadOfOverwritingEarlierRules() {
        BatchExecutor.registerSkipRule(details -> details == null);
        BatchExecutor.registerSkipRule(details -> false);
        BatchExecutor.registerBatchEligibilityRule(details -> details != null);
        BatchExecutor.registerBatchEligibilityRule(details -> true);

        assertTrue(BatchExecutor.shouldSkip(null));
        assertFalse(BatchExecutor.isBatchEligible(null));
    }
}
