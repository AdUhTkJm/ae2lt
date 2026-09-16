package com.moakiee.thunderbolt.api.crafting;

/** Computation budget, staged cancellation and diagnostics for one planning-engine candidate. */
public interface PlanningAttemptContext {
    /** Absolute {@link System#nanoTime()} computation deadline. */
    long deadlineNanos();

    /**
     * Throws {@link PlanningExitException} after the budget or when the router forwards an outer
     * calculation cancellation.
     */
    void checkpoint();

    /** Replaces the latest diagnostic snapshot shown when this candidate is slow or times out. */
    void report(PlanningDiagnosticSnapshot snapshot);
}
