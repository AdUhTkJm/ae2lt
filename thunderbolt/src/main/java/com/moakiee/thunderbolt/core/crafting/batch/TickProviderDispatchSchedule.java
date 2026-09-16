package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.ArrayList;
import java.util.IdentityHashMap;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.me.service.CraftingService;

/**
 * Per-physical-CPU provider schedule for one server tick.
 *
 * <p>Provider candidates are snapshotted once per canonical provider-pattern identity. A provider that
 * rejects that pattern is removed for the rest of the tick, while the most recently successful
 * provider is tried first. This makes a long failed prefix an O(n) cost once per tick instead of
 * once per successful dispatch.
 */
public final class TickProviderDispatchSchedule {
    private final IdentityHashMap<IPatternDetails, PatternSchedule> patterns = new IdentityHashMap<>();
    private long tick = Long.MIN_VALUE;

    public void beginTick(long currentTick) {
        if (tick == currentTick) return;
        tick = currentTick;
        patterns.clear();
    }

    public Iterable<ICraftingProvider> candidates(
            CraftingService craftingService,
            IPatternDetails providerLookupPattern,
            IPatternDetails canonicalPattern) {
        var schedule = patterns.computeIfAbsent(
                canonicalPattern,
                ignored -> snapshotProviders(craftingService, providerLookupPattern));
        return schedule.candidates;
    }

    private static PatternSchedule snapshotProviders(
            CraftingService craftingService,
            IPatternDetails providerLookupPattern) {
        var providers = new ArrayList<ICraftingProvider>();
        for (var provider : craftingService.getProviders(providerLookupPattern)) {
            providers.add(provider);
        }
        return new PatternSchedule(providers);
    }

    public boolean isBlocked(IPatternDetails canonicalPattern, ICraftingProvider provider) {
        var schedule = patterns.get(canonicalPattern);
        return schedule != null && schedule.candidates.isBlocked(provider);
    }

    public void recordFailure(IPatternDetails canonicalPattern, ICraftingProvider provider) {
        var schedule = patterns.computeIfAbsent(canonicalPattern, ignored -> new PatternSchedule(java.util.List.of()));
        schedule.candidates.block(provider);
    }

    public void recordSuccess(IPatternDetails canonicalPattern, ICraftingProvider provider) {
        var schedule = patterns.get(canonicalPattern);
        if (schedule != null) schedule.candidates.markSuccess(provider);
    }

    public int blockedCount(IPatternDetails canonicalPattern) {
        var schedule = patterns.get(canonicalPattern);
        return schedule != null ? schedule.candidates.blockedCount() : 0;
    }

    private static final class PatternSchedule {
        private final IdentityCandidateQueue<ICraftingProvider> candidates;

        private PatternSchedule(java.util.List<ICraftingProvider> providers) {
            this.candidates = new IdentityCandidateQueue<>(providers);
        }
    }
}
