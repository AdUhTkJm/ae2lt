package com.moakiee.thunderbolt.mixin.platform.eject;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;

@Mixin(Level.class)
public abstract class EjectGhostBlockEntityMixin {
    @Inject(method = "getBlockEntity", at = @At("RETURN"), cancellable = true)
    private void thunderbolt$injectGhostBlockEntity(
            BlockPos pos, CallbackInfoReturnable<BlockEntity> callback) {
        if (callback.getReturnValue() != null
                || EjectEndpointIndex.INSTANCE.isEmpty()
                || !((Object) this instanceof ServerLevel)) return;
        var level = (Level) (Object) this;
        var entry = EjectEndpointIndex.INSTANCE.lookupAny(level.dimension(), pos.asLong());
        if (entry == null) return;
        var ghost = entry.ghostBlockEntity();
        if (ghost.getLevel() == null) ghost.setLevel(level);
        callback.setReturnValue(ghost);
    }
}
