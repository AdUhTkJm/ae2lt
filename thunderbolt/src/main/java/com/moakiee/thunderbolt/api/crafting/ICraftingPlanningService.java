package com.moakiee.thunderbolt.api.crafting;

import java.util.List;

import appeng.api.networking.IGridService;

/** Builds an immutable algorithm chain from public algorithms and online provider nodes. */
public interface ICraftingPlanningService extends IGridService {
    /** The returned chain is frozen for one CraftingCalculation and always contains vanilla. */
    List<PlanningChoice> resolve();
}
