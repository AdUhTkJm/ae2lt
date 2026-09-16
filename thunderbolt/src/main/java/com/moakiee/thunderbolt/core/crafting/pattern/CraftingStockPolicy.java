package com.moakiee.thunderbolt.core.crafting.pattern;

import java.util.Map;

import appeng.api.stacks.AEKey;

/** Core requester policy limiting pre-existing network stock visible to the fast planner. */
public interface CraftingStockPolicy {

    long usablePreexistingStock(AEKey key, long snapshotAmount);

    default boolean groupsSecondaryVariants(AEKey key) {
        return false;
    }

    default long usablePreexistingStock(
            AEKey exactVariant, long exactAmount, Map<AEKey, Long> groupSnapshot) {
        return usablePreexistingStock(exactVariant, exactAmount);
    }
}
