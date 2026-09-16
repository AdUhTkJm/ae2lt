package com.moakiee.thunderbolt.ae2.crafting;

import java.util.List;

import appeng.api.networking.IGrid;

import com.moakiee.thunderbolt.api.crafting.PlanningChoice;

/** Internal bridge implemented on AE2 crafting calculations by the Mixin layer. */
public interface CraftingPlanningControl {
    void thunderbolt$configurePlanning(List<PlanningChoice> candidates, IGrid grid);
}
