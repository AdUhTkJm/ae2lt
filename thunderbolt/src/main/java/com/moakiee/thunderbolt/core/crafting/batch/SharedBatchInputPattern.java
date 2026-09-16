package com.moakiee.thunderbolt.core.crafting.batch;

import appeng.api.stacks.AEKey;

/**
 * Neutral pattern-side declaration for inputs that are returned and shared by an accepted batch.
 */
public interface SharedBatchInputPattern {

    boolean isSharedBatchInput(int slot, AEKey concreteKey);

    /** Portion of this output that represents the one reusable input returned for the batch. */
    default long sharedBatchOutputAmount(AEKey outputKey) {
        return 0L;
    }
}
