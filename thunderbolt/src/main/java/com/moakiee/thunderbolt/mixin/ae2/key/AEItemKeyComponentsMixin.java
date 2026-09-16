package com.moakiee.thunderbolt.mixin.ae2.key;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;

/** Restores the documented AEKey component contract for AE2 19.2.x item keys. */
@Mixin(value = AEItemKey.class, remap = false)
abstract class AEItemKeyComponentsMixin {
    @Shadow
    public abstract ItemStack getReadOnlyStack();

    @ModifyReturnValue(method = "hasComponents", at = @At("RETURN"), require = 1)
    private boolean thunderbolt$reportComponentPatch(boolean original) {
        return thunderbolt$hasComponentPatch(getReadOnlyStack());
    }

    @Unique
    private static boolean thunderbolt$hasComponentPatch(ItemStack stack) {
        // ItemStack#getComponents includes the item's immutable defaults. Only the patch can
        // distinguish two stacks of the same item, which is what AE2's item-only recipe cache
        // needs to guard against.
        return !stack.isComponentsPatchEmpty();
    }
}
