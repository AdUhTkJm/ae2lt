package com.moakiee.thunderbolt.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptionalMixinSelectorTest {
    @Test
    void skipsAdvancedAeTargetsWhenAddonIsMissing() {
        assertFalse(OptionalMixinSelector.shouldApply(
                "com.moakiee.thunderbolt.mixin.compat.advancedae.AdvCraftingCpuLogicBatchMixin",
                ignored -> false));
        assertFalse(OptionalMixinSelector.shouldApply("AaeTaskProgressAccessor", ignored -> false));
    }

    @Test
    void skipsExtendedAePlusTargetWhenAddonIsMissing() {
        assertFalse(OptionalMixinSelector.shouldApply(
                "ExtendedAePlusSuperMatrixBatchMixin",
                ignored -> false));
    }

    @Test
    void appliesOptionalTargetsWhenTheirModIsLoaded() {
        assertTrue(OptionalMixinSelector.shouldApply("AdvCraftingCpuAccessor", "advanced_ae"::equals));
        assertTrue(OptionalMixinSelector.shouldApply(
                "ExtendedAePlusSuperMatrixBatchMixin",
                "extendedae_plus"::equals));
    }

    @Test
    void neverGatesRequiredMixins() {
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCalculationMixin", ignored -> false));
    }
}
