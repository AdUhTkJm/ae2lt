package com.moakiee.thunderbolt.api.crafting;

import appeng.api.networking.crafting.ICraftingPlan;

/** Per-calculation engine state. A session is confined to one candidate worker thread. */
public interface PlanningEngineSession extends AutoCloseable {
    /**
     * Runs one amount probe. Implementations must periodically call
     * {@link PlanningAttemptContext#checkpoint()} and may publish diagnostics.
     */
    PlanningAttempt attempt(long amount, boolean simulate, PlanningAttemptContext context);

    /** Allows an engine to attach plan-level execution metadata after AE2 finishes probing. */
    default ICraftingPlan finish(ICraftingPlan result, PlanningAttemptContext context) {
        return result;
    }

    /** Releases candidate-local state after success, decline, failure or cooperative exit. */
    @Override
    default void close() {
    }
}
