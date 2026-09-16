package com.moakiee.thunderbolt.api.crafting;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import net.minecraft.world.level.Level;

/** Common calculation input shared by every planning candidate. */
public record PlanningRequest(
        Level level,
        ICraftingService craftingService,
        NetworkCraftingSimulationState networkInventory,
        AEKey output,
        long requestedAmount,
        CalculationStrategy strategy,
        ICraftingSimulationRequester requester) {
}
