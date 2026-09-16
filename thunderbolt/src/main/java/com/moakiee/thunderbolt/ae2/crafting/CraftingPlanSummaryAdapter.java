package com.moakiee.thunderbolt.ae2.crafting;

import java.util.Map;
import java.util.Objects;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;

import com.moakiee.thunderbolt.core.crafting.plan.LoopCraftingPlan;

/** Builds concrete, display-only AE2 plans for integrations that cast the public plan interface. */
public final class CraftingPlanSummaryAdapter {
    private CraftingPlanSummaryAdapter() {
    }

    /**
     * Returns a concrete AE2 plan containing the interface-visible state of {@code plan}.
     *
     * <p>The returned value must only be passed to summary/display code. The original plan remains
     * authoritative for menu storage and CPU submission because third-party implementations may
     * carry routing, reservation, or execution metadata outside {@link ICraftingPlan}.
     */
    public static CraftingPlan adapt(ICraftingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan instanceof CraftingPlan craftingPlan) {
            return craftingPlan;
        }
        if (plan instanceof LoopCraftingPlan loopPlan) {
            return loopPlan.delegate();
        }
        return new CraftingPlan(
                plan.finalOutput(),
                plan.bytes(),
                plan.simulation(),
                plan.multiplePaths(),
                copyCounter(plan.usedItems()),
                copyCounter(plan.emittedItems()),
                copyCounter(plan.missingItems()),
                Map.copyOf(plan.patternTimes()));
    }

    private static KeyCounter copyCounter(KeyCounter source) {
        Objects.requireNonNull(source, "plan counter");
        var result = new KeyCounter();
        for (var entry : source) {
            result.add(entry.getKey(), entry.getLongValue());
        }
        return result;
    }
}
