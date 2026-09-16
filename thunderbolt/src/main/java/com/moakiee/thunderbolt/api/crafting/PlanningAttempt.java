package com.moakiee.thunderbolt.api.crafting;

import org.jetbrains.annotations.Nullable;

import appeng.crafting.CraftingPlan;

/** Result of one amount probe. HANDLED with a null plan is an authoritative infeasible result. */
public record PlanningAttempt(
        Status status,
        @Nullable CraftingPlan plan,
        @Nullable CraftingPlan simulationFallback) {
    public enum Status {
        DECLINE,
        HANDLED
    }

    public static final PlanningAttempt DECLINE = new PlanningAttempt(Status.DECLINE, null, null);

    public static PlanningAttempt handled(@Nullable CraftingPlan plan) {
        return new PlanningAttempt(Status.HANDLED, plan, null);
    }

    public static PlanningAttempt infeasible(@Nullable CraftingPlan simulationFallback) {
        return new PlanningAttempt(Status.HANDLED, null, simulationFallback);
    }
}
