package com.moakiee.thunderbolt.core.crafting.pattern;

import appeng.api.crafting.IPatternDetails;

/**
 * Optional contract for task wrappers that delegate physical batch execution to another pattern.
 *
 * <p>This is deliberately separate from {@link IProviderLookupPattern}: provider discovery may
 * unwrap through multiple metadata layers, while execution must retain every layer that affects
 * physical inputs, outputs, capacity or assembly behavior.
 */
public interface IBatchExecutionPattern {
    IPatternDetails batchExecutionPattern();
}
