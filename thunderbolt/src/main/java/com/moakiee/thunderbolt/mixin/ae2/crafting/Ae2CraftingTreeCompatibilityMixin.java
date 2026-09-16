package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;

import com.moakiee.thunderbolt.ae2.crafting.CraftingPlanSummaryAdapter;

/**
 * Gives summary-only integrations a concrete AE2 plan while retaining the original plan for
 * confirmation-menu storage and CPU submission.
 */
@Mixin(value = CraftingPlanSummary.class, priority = 2000, remap = false)
public abstract class Ae2CraftingTreeCompatibilityMixin {

    @ModifyVariable(method = "fromJob", at = @At("HEAD"), argsOnly = true, index = 2)
    private static ICraftingPlan thunderbolt$adaptPlanForAe2CraftingTree(ICraftingPlan job) {
        return CraftingPlanSummaryAdapter.adapt(job);
    }
}
