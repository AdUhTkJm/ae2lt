package com.moakiee.thunderbolt.core.crafting.algorithm;

/** Internal control signal that restarts the complete calculation with the next candidate. */
public final class PlanningCandidateDeclinedException extends RuntimeException {
    public PlanningCandidateDeclinedException() {
        super(null, null, false, false);
    }
}
