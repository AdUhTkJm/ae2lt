package com.moakiee.thunderbolt.core.crafting.pattern;

/**
 * Optional closure metadata for late-bound, same-primary-identity pattern semantics.
 *
 * <p>Input candidates are always discovered from AE2's native
 * {@link appeng.api.crafting.IPatternDetails.IInput#getPossibleInputs()} anchors and validated with
 * {@link appeng.api.crafting.IPatternDetails.IInput#isValid}. Implementing this interface must not be
 * required merely to expose tags, component-insensitive inputs, or another custom accepted-key set.
 */
public interface FuzzyPatternInputs {

    /**
     * Declares that every key sharing the primary identity of this slot's possible-input anchors is
     * valid for the slot. The planner uses this as a closure proof when connecting a late-bound
     * same-id output; it is not the source of the slot's concrete input candidates.
     */
    boolean acceptsSameIdVariants(int slot);

    /**
     * Whether the output slot is late-bound to an actual key with the same primary identity as its
     * declared concrete output. Such an output may satisfy an input covered by
     * {@link #acceptsSameIdVariants(int)}, but never a strict or merely partially-overlapping demand.
     * The executing integration remains responsible for accounting the actual produced key.
     */
    default boolean producesSameIdVariants(int slot) {
        return false;
    }
}
