package com.moakiee.thunderbolt.core.crafting.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import net.minecraft.resources.ResourceLocation;

class CraftingAlgorithmCalculationStatusTest {
    @Test
    void selectedAlgorithmBridgesRequesterToItsCalculationFuture() {
        ICraftingSimulationRequester requester = () -> null;
        var future = new CompletableFuture<ICraftingPlan>();
        var algorithm = ResourceLocation.fromNamespaceAndPath("test", "planner");

        var tracked = CraftingAlgorithmCalculationStatus.track(requester, () -> future);
        CraftingAlgorithmCalculationStatus.select(requester, algorithm);

        assertSame(future, tracked);
        assertEquals(algorithm, CraftingAlgorithmCalculationStatus.selected(future));

        CraftingAlgorithmCalculationStatus.forget(future);
        assertNull(CraftingAlgorithmCalculationStatus.selected(future));
    }
}
