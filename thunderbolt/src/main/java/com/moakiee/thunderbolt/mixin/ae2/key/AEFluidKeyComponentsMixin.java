package com.moakiee.thunderbolt.mixin.ae2.key;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import net.neoforged.neoforge.fluids.FluidStack;

import appeng.api.stacks.AEFluidKey;

/** Restores the documented AEKey component contract for AE2 19.2.x fluid keys. */
@Mixin(value = AEFluidKey.class, remap = false)
abstract class AEFluidKeyComponentsMixin {
    @Shadow
    @Final
    private FluidStack stack;

    @ModifyReturnValue(method = "hasComponents", at = @At("RETURN"), require = 1)
    private boolean thunderbolt$reportComponentPatch(boolean original) {
        return thunderbolt$hasComponentPatch(stack);
    }

    @Unique
    private static boolean thunderbolt$hasComponentPatch(FluidStack stack) {
        return !stack.isComponentsPatchEmpty();
    }
}
