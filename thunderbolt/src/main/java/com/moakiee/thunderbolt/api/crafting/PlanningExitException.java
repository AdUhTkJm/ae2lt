package com.moakiee.thunderbolt.api.crafting;

/**
 * Candidate-local signal to stop searching and immediately return the current best result or
 * {@link PlanningAttempt#DECLINE}. The router separately retains whether the cause was a budget
 * timeout or an outer calculation cancellation.
 */
public final class PlanningExitException extends RuntimeException {
    public PlanningExitException(String message) {
        super(message);
    }
}
