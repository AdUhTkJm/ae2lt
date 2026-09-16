package com.moakiee.thunderbolt.core.crafting.overload;

import com.moakiee.thunderbolt.core.crafting.pattern.FuzzyPatternInputs;

/**
 * Planner-facing contract for a pattern whose slot matching is broader than AE2's encoded
 * exact keys. Product code may add host-specific metadata without exposing that implementation to
 * Thunderbolt's planner.
 */
public interface OverloadedPatternDetails extends FuzzyPatternInputs {

    /** Stable identity of this overload definition within one crafting job. */
    String overloadPatternIdentity();

    /** True when at least one input slot accepts same-id variants. */
    boolean hasFuzzyInputs();

    /** True when this input slot accepts keys with the same primary identity. */
    boolean isFuzzyInput(int slot);

    /** True when this output slot may produce any key with the same primary identity. */
    boolean isFuzzyOutput(int slot);

    @Override
    default boolean acceptsSameIdVariants(int slot) {
        return isFuzzyInput(slot);
    }

    @Override
    default boolean producesSameIdVariants(int slot) {
        return isFuzzyOutput(slot);
    }
}
