package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;

/** Short-lived bridge from a planning-engine session to Thunderbolt's plan finalizer. */
public final class PlanningMetadataStore {

    private static final Map<CraftingPlan, Map<ReusableStockUsageKey<AEKey>, Long>> REUSABLE_STOCK =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private PlanningMetadataStore() {
    }

    public static void record(
            CraftingPlan plan, Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock) {
        if (plan != null && usedReusableStock != null && !usedReusableStock.isEmpty()) {
            REUSABLE_STOCK.put(plan, Map.copyOf(usedReusableStock));
        }
    }

    public static Map<ReusableStockUsageKey<AEKey>, Long> take(CraftingPlan plan) {
        var metadata = REUSABLE_STOCK.remove(plan);
        return metadata != null ? metadata : Map.of();
    }
}
