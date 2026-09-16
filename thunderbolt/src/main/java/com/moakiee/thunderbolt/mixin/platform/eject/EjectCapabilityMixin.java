package com.moakiee.thunderbolt.mixin.platform.eject;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import com.moakiee.thunderbolt.api.eject.EjectOfflinePolicy;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;

// Run before third-party capability interceptors. Once a registered Thunderbolt EJECT endpoint
// supplies a result, the cancellable HEAD injection returns from getCapability immediately and
// lower-priority interceptors cannot replace ownership of that endpoint.
@Mixin(value = BlockCapability.class, priority = 2000)
public abstract class EjectCapabilityMixin<T, C> {
    @Unique
    private static final ThreadLocal<Boolean> THUNDERBOLT_PROXYING =
            ThreadLocal.withInitial(() -> false);

    @Unique
    private static final IItemHandler THUNDERBOLT_REJECTING_ITEM_HANDLER = new IItemHandler() {
        @Override public int getSlots() { return 1; }
        @Override public ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 0; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    };

    @Unique
    private static final IFluidHandler THUNDERBOLT_REJECTING_FLUID_HANDLER = new IFluidHandler() {
        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
        @Override public int getTankCapacity(int tank) { return 0; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return false; }
        @Override public int fill(FluidStack resource, FluidAction action) { return 0; }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
        @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
    };

    @SuppressWarnings("unchecked")
    @Inject(method = "getCapability", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$interceptEjectCapability(
            Level level,
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            C context,
            CallbackInfoReturnable<T> callback) {
        // Registry-empty is the overwhelmingly common case on this global hot path; check it
        // before paying the ThreadLocal lookup.
        if (EjectEndpointIndex.INSTANCE.isEmpty()
                || THUNDERBOLT_PROXYING.get()
                || EjectEndpointIndex.INSTANCE.isBypassed()
                || !(level instanceof ServerLevel)
                || !(context instanceof Direction face)) {
            return;
        }

        var entry = EjectEndpointIndex.INSTANCE.lookupByFace(level.dimension(), pos.asLong(), face);
        if (entry == null) return;
        var host = EjectEndpointIndex.INSTANCE.resolveHost(entry);
        if (host == null) {
            if (entry.endpoint().offlinePolicy() == EjectOfflinePolicy.ABSENT) return;
            var capability = (BlockCapability<T, C>) (Object) this;
            if (capability == Capabilities.ItemHandler.BLOCK) {
                callback.setReturnValue((T) THUNDERBOLT_REJECTING_ITEM_HANDLER);
            } else if (capability == Capabilities.FluidHandler.BLOCK) {
                callback.setReturnValue((T) THUNDERBOLT_REJECTING_FLUID_HANDLER);
            }
            return;
        }

        var hostLevel = host.getLevel();
        if (hostLevel == null) return;
        var hostPos = host.getBlockPos();
        THUNDERBOLT_PROXYING.set(true);
        try {
            var capability = (BlockCapability<T, C>) (Object) this;
            var result = capability.getCapability(
                    hostLevel,
                    hostPos,
                    hostLevel.getBlockState(hostPos),
                    host,
                    context);
            if (result != null) callback.setReturnValue(result);
        } finally {
            THUNDERBOLT_PROXYING.remove();
        }
    }
}
