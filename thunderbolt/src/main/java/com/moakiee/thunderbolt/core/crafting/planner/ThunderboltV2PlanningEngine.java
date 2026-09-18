package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.IdentityHashMap;
import java.util.Map;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;
import com.moakiee.thunderbolt.api.crafting.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.ThunderboltCore;
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
        var graphSession = FastCraftingPlanner.CalculationSession.v2();
        return new BasicPlanningEngineSession(request, graphSession);
    }
}
