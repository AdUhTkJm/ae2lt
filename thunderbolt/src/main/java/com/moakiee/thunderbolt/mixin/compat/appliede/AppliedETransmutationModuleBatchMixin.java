package com.moakiee.thunderbolt.mixin.compat.appliede;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.parts.AEBasePart;
import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.crafting.batch.BatchDispatchMode;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.compat.appliede.AppliedEModuleBatchSupport;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** AppliedE 1.x module: one native pending-output update replaces repeated single-copy pushes. */
@Pseudo
@Mixin(targets = "gripe._90.appliede.part.EMCModulePart", remap = false)
public abstract class AppliedETransmutationModuleBatchMixin implements IBatchCraftingProvider {
    @Shadow @Final private Object2LongMap<AEKey> outputs;

    @Override
    public BatchDispatchMode getBatchDispatchMode(IPatternDetails details) {
        return BatchDispatchMode.UNBOUNDED;
    }

    @Override
    public long getBatchCapacity(IPatternDetails details) {
        var self = (AEBasePart) (Object) this;
        if (!self.getMainNode().isActive()) return 0L;
        return Math.min(
                AppliedEModuleBatchSupport.capacity(thunderbolt$primaryOutput(details), outputs),
                CoreConfig.batchCopyLimitRules().limit("appliede:emc_module"));
    }

    @Override
    public long pushBatch(IPatternDetails details, KeyCounter[] oneCopyTemplate, long maxCraft) {
        if (maxCraft <= 0L) return 0L;
        long accepted = Math.min(maxCraft, getBatchCapacity(details));
        if (accepted <= 0L) return maxCraft;
        var self = (AEBasePart) (Object) this;
        // Alert before changing the queue so a failed alert cannot leave unpaid outputs behind
        // when BatchExecutor refunds the batch. Alerting schedules a later grid tick.
        if (!self.getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node))) {
            return maxCraft;
        }
        long leftover = AppliedEModuleBatchSupport.enqueue(
                thunderbolt$primaryOutput(details), outputs, accepted);
        return maxCraft - (accepted - leftover);
    }

    @Unique
    private static @Nullable GenericStack thunderbolt$primaryOutput(IPatternDetails details) {
        // The native pattern is final. Check its exact type without loading an optional addon
        // during Thunderbolt startup; both item transmutation and EMC tier conversion are valid.
        return details != null && details.getClass().getName().equals(
                "gripe._90.appliede.me.service.TransmutationPattern")
                ? details.getPrimaryOutput() : null;
    }
}
