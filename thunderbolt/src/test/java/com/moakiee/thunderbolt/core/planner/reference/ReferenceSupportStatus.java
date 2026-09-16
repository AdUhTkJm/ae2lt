package com.moakiee.thunderbolt.core.crafting.planner.reference;

/** Observable production-path classification for one reference case. */
public enum ReferenceSupportStatus {
    /** The report is usable and matches a known material kind set with enough total quantity. */
    SUPPORTED,
    /** A unique minimum differs, but supplying the report still produces a valid plan. */
    PARTIALLY_SUPPORTED,
    /** Refill succeeds, but non-unique minima make a different material kind set incomparable. */
    UNKNOWN,
    CHECK_REJECTED,
    /** The engine declined initially or after its reported missing was supplied. */
    ATTEMPT_DECLINED,
    /** The scenario inventory was sufficient, but the engine reported that it was not. */
    FALSE_NEGATIVE,
    ENGINE_ERROR,
    ENGINE_TIMEOUT,
    NON_COOPERATIVE_TIMEOUT,
    /** The engine claimed feasible with no missing, but its reported execution cannot complete. */
    FALSE_POSITIVE
}
