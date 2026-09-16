package com.moakiee.thunderbolt.core.crafting.support;

import appeng.api.networking.crafting.ICraftingService;
import appeng.hooks.ticking.TickHandler;

/** Detects changes to AE2's mounted crafting-provider set. */
public final class CraftingProviderChangeTracker {
    /**
     * Mixin-facing view of AE2's provider-change timestamp.
     *
     * <p>This bridge keeps ordinary runtime consumers out of the reserved Mixin package and keeps
     * core independent of concrete Mixin implementation classes.
     */
    public interface ProviderStateView {
        long thunderbolt$getCraftingProvidersLastModifiedOnTick();
    }

    private long lastCheckTick = Long.MIN_VALUE;

    /**
     * Returns true when provider-dependent state should be recomputed.
     *
     * <p>An equality check intentionally triggers once more on the following tick. AE2 timestamps
     * provider changes at tick granularity, so another provider may change later in the same tick
     * after the caller has already checked.
     */
    public boolean shouldRecheck(ICraftingService service) {
        if (!(service instanceof ProviderStateView providerState)) return true;
        return shouldRecheck(
                providerState.thunderbolt$getCraftingProvidersLastModifiedOnTick(),
                TickHandler.instance().getCurrentTick());
    }

    boolean shouldRecheck(long changedTick, long currentTick) {
        if (changedTick < lastCheckTick) return false;
        lastCheckTick = currentTick;
        return true;
    }

    public void reset() {
        lastCheckTick = Long.MIN_VALUE;
    }
}
