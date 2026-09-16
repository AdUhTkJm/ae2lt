package com.moakiee.thunderbolt.api.crafting;

import appeng.api.networking.IGrid;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** A server-side crafting-plan calculation implementation. */
public interface CraftingPlanningEngine {
    ResourceLocation id();

    /** Player-facing, translatable algorithm name. */
    default Component getName() {
        return Component.literal(id().toString());
    }

    /**
     * Performs the cheap runtime availability and request-support check on the Grid thread. This
     * method must be bounded and non-blocking because it runs before worker submission.
     */
    boolean check(IGrid grid, PlanningRequest request);

    /**
     * Captures engine-specific Grid data before the calculation is submitted to its worker. This
     * method must be bounded and non-blocking because it runs on the caller's Grid thread.
     * Returning {@code null} means that this engine needs no additional captured input. A returned
     * object must be safe to consume without reading the live Grid from the worker thread.
     */
    default @Nullable Object capture(IGrid grid, PlanningRequest request) {
        return null;
    }

    /** Creates, on the worker thread, the state reused by every probe in one calculation. */
    @Nullable
    PlanningEngineSession createSession(
            PlanningRequest request,
            @Nullable Object capturedInput,
            PlanningAttemptContext context);
}
