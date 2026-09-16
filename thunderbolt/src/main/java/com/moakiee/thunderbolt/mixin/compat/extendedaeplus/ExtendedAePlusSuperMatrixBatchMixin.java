package com.moakiee.thunderbolt.mixin.compat.extendedaeplus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;

import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.compat.extendedaeplus.ExtendedAePlusSuperMatrixBatchBridge;

/** Adds Thunderbolt batch dispatch only to EAEP's own Super Assembler Matrix provider. */
@Pseudo
@Mixin(
        targets = "com.extendedae_plus.content.matrix.supermatrix.SuperAssemblerMatrixBlockEntity",
        remap = false)
public abstract class ExtendedAePlusSuperMatrixBatchMixin implements IBatchCraftingProvider {
    @Unique
    private long thunderbolt$batchLimitRulesVersion = Long.MIN_VALUE;
    @Unique
    private long thunderbolt$batchCopyLimit = Long.MAX_VALUE;

    @Override
    public long getBatchCapacity(IPatternDetails details) {
        var self = (ICraftingProvider) (Object) this;
        if (self.isBusy()) {
            return 0L;
        }
        return Math.min(
                ExtendedAePlusSuperMatrixBatchBridge.capacity(details),
                thunderbolt$batchCopyLimit());
    }

    @Override
    public long pushBatch(
            IPatternDetails details,
            KeyCounter[] oneCopyTemplate,
            long maxCraft) {
        long limited = Math.min(maxCraft, thunderbolt$batchCopyLimit());
        if (limited <= 0L) {
            return maxCraft;
        }
        long limitedLeftover = ExtendedAePlusSuperMatrixBatchBridge.pushBatch(
                (ICraftingProvider) (Object) this,
                details,
                oneCopyTemplate,
                limited);
        long accepted = limited - Math.clamp(limitedLeftover, 0L, limited);
        return maxCraft - accepted;
    }

    @Unique
    private long thunderbolt$batchCopyLimit() {
        var rules = CoreConfig.batchCopyLimitRules();
        if (thunderbolt$batchLimitRulesVersion != rules.version()) {
            var self = (BlockEntity) (Object) this;
            var blockId = BuiltInRegistries.BLOCK.getKey(self.getBlockState().getBlock());
            thunderbolt$batchCopyLimit = blockId != null
                    ? rules.limit(blockId.toString())
                    : Long.MAX_VALUE;
            thunderbolt$batchLimitRulesVersion = rules.version();
        }
        return thunderbolt$batchCopyLimit;
    }
}
