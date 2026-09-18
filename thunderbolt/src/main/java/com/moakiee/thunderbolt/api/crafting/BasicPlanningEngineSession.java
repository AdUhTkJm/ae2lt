package com.moakiee.thunderbolt.api.crafting;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;
import com.moakiee.thunderbolt.core.crafting.pattern.CraftingStockPolicy;
import com.moakiee.thunderbolt.core.crafting.planner.FastCraftingPlanner;
import com.moakiee.thunderbolt.core.crafting.planner.PlanningMetadataStore;
import com.moakiee.thunderbolt.core.crafting.planner.ReusableStockUsageKey;

import java.util.IdentityHashMap;
import java.util.Map;

public class BasicPlanningEngineSession implements PlanningEngineSession {
    private final PlanningRequest request;
    private final FastCraftingPlanner.CalculationSession graphSession;
    private final Map<CraftingPlan, Map<ReusableStockUsageKey<AEKey>, Long>> reusableStock =
        new IdentityHashMap<>();

    public BasicPlanningEngineSession(PlanningRequest request, FastCraftingPlanner.CalculationSession graphSession) {
        this.request = request;
        this.graphSession = graphSession;
    }

    @Override
    public PlanningAttempt attempt(
        long amount, boolean simulate, PlanningAttemptContext context) {
        final FastCraftingPlanner.FastAttempt result;
        try {
            context.checkpoint();
            result = FastCraftingPlanner.tryAttempt(
                request.craftingService(), request.networkInventory(), request.level(),
                request.output(), amount, simulate,
                request.requester() instanceof CraftingStockPolicy policy ? policy : null,
                graphSession);
        } catch (PlanningExitException exit) {
            return PlanningAttempt.DECLINE;
        }
        if (!result.handled()) {
            return PlanningAttempt.DECLINE;
        }
        if (result.plan() != null) {
            reusableStock.put(result.plan(), result.usedReusableStock());
        }
        if (result.simulationFallback() != null) {
            reusableStock.put(result.simulationFallback(), result.usedReusableStock());
        }
        return new PlanningAttempt(
            PlanningAttempt.Status.HANDLED,
            result.plan(),
            result.simulationFallback());
    }

    @Override
    public ICraftingPlan finish(ICraftingPlan result, PlanningAttemptContext context) {
        context.report(PlanningDiagnosticSnapshot.phase("finishing"));
        if (result instanceof CraftingPlan craftingPlan) {
            var used = reusableStock.get(craftingPlan);
            if (used != null) {
                PlanningMetadataStore.record(craftingPlan, used);
            }
        }
        return result;
    }

    @Override
    public void close() {
        reusableStock.clear();
    }
}
