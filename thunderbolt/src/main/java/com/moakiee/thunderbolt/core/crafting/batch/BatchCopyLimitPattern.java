package com.moakiee.thunderbolt.core.crafting.batch;

/** Pattern-side upper bound for one CPU batch dispatch. */
public interface BatchCopyLimitPattern {

    long maxBatchCopies();
}
