package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.IdentityHashMap;
import java.util.Map;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine;
import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;
import com.moakiee.thunderbolt.core.crafting.pattern.CraftingStockPolicy;

/** Adapter that exposes Thunderbolt's V2 planner through the multi-algorithm API. */
public final class ThunderboltV2PlanningEngine implements CraftingPlanningEngine {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            ThunderboltCore.MODID, "v2");
    public static final ThunderboltV2PlanningEngine INSTANCE = new ThunderboltV2PlanningEngine();

    private ThunderboltV2PlanningEngine() {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component getName() {
        return Component.translatable("algorithm.thunderbolt.v2");
    }

    @Override
    public boolean check(IGrid grid, PlanningRequest request) {
        return request.requestedAmount() > 0
                && request.output() != null
                && request.requester().getGridNode() != null
                && request.requester().getGridNode().getGrid() == grid;
    }

    @Override
    public PlanningEngineSession createSession(
            PlanningRequest request,
            @Nullable Object capturedInput,
            PlanningAttemptContext context) {
        return new Session(request);
    }

    private static final class Session implements PlanningEngineSession {
        private final PlanningRequest request;
        private final FastCraftingPlanner.CalculationSession delegate =
                new FastCraftingPlanner.CalculationSession();
        private final Map<CraftingPlan, Map<ReusableStockUsageKey<AEKey>, Long>> reusableStock =
                new IdentityHashMap<>();

        private Session(PlanningRequest request) {
            this.request = request;
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
                        delegate);
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
}
