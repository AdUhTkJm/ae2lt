package com.moakiee.thunderbolt.mixin;

import java.util.Map;
import java.util.function.Predicate;

/** Pure target-to-mod mapping used by the early Mixin config plugin. */
public final class OptionalMixinSelector {
    private static final Map<String, String> REQUIRED_MODS = Map.ofEntries(
            Map.entry("AdvCraftingCpuLogicBatchMixin", "advanced_ae"),
            Map.entry("AdvCraftingCpuAccessor", "advanced_ae"),
            Map.entry("AaeExecutingCraftingJobAccessor", "advanced_ae"),
            Map.entry("AaeElapsedTimeTrackerAccessor", "advanced_ae"),
            Map.entry("AaeTaskProgressAccessor", "advanced_ae"),
            Map.entry("ExtendedAePlusSuperMatrixBatchMixin", "extendedae_plus"),
            Map.entry("AppliedETransmutationModuleBatchMixin", "appliede"));

    private OptionalMixinSelector() {
    }

    public static boolean shouldApply(String mixinClassName, Predicate<String> modLoaded) {
        int separator = mixinClassName.lastIndexOf('.');
        String simpleName = separator >= 0 ? mixinClassName.substring(separator + 1) : mixinClassName;
        String requiredMod = REQUIRED_MODS.get(simpleName);
        return requiredMod == null || modLoaded.test(requiredMod);
    }
}
