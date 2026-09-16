package com.moakiee.thunderbolt.mixin.ae2.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.core.localization.Tooltips;

@Mixin(value = Tooltips.class, remap = false)
public final class TooltipsByteAmountMixin {
    private TooltipsByteAmountMixin() {
    }

    @Inject(method = "getByteAmount", at = @At("HEAD"), cancellable = true)
    private static void thunderbolt$getInfiniteByteAmount(
            long amount,
            CallbackInfoReturnable<Tooltips.Amount> cir) {
        if (amount == Long.MAX_VALUE) {
            cir.setReturnValue(new Tooltips.Amount("∞", ""));
        }
    }
}
