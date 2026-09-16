package com.moakiee.thunderbolt.core.crafting.pattern;

import java.util.Map;

import appeng.api.stacks.AEKey;

/** Core pattern metadata consumed by the fast planner for job-scoped reusable stock. */
public interface ReusableStockPattern {

    ReusableStockSource reusableStockSource();

    Map<AEKey, Long> reusableStockRequirements();

    default boolean acceptsReusableStockVariant(AEKey planned, AEKey actual) {
        return planned != null && planned.equals(actual);
    }

    default Map<AEKey, Long> availableReusableStockSnapshot() {
        return Map.of();
    }
}
