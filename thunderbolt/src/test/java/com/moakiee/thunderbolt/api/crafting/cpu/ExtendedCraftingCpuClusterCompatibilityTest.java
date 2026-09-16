package com.moakiee.thunderbolt.api.crafting.cpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;

import org.junit.jupiter.api.Test;

class ExtendedCraftingCpuClusterCompatibilityTest {
    @Test
    void extendedCpuDefaultMatchesVanillaCompatibility() {
        var cluster = defaultCluster();

        assertTrue(cluster.canHandle(nativePlan()));
        assertFalse(cluster.canHandle(thirdPartyPlan()));
    }

    private static ExtendedCraftingCpuCluster defaultCluster() {
        return (ExtendedCraftingCpuCluster) Proxy.newProxyInstance(
                ExtendedCraftingCpuCluster.class.getClassLoader(),
                new Class<?>[]{ExtendedCraftingCpuCluster.class},
                (proxy, method, args) -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    throw new AssertionError("unexpected method: " + method);
                });
    }

    private static CraftingPlan nativePlan() {
        return new CraftingPlan(
                null, 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
    }

    private static ICraftingPlan thirdPartyPlan() {
        return new ThirdPartyPlan(
                null, 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
    }

    private record ThirdPartyPlan(
            GenericStack finalOutput,
            long bytes,
            boolean simulation,
            boolean multiplePaths,
            KeyCounter usedItems,
            KeyCounter emittedItems,
            KeyCounter missingItems,
            Map<IPatternDetails, Long> patternTimes) implements ICraftingPlan {
    }
}
