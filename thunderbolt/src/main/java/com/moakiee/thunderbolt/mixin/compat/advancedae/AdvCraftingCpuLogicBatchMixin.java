package com.moakiee.thunderbolt.mixin.compat.advancedae;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.Level;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.core.crafting.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.core.crafting.batch.BatchExecutor;
import com.moakiee.thunderbolt.core.crafting.batch.BatchProviderFilterIterable;
import com.moakiee.thunderbolt.core.crafting.batch.DefaultBatchJobView;

@Pseudo
@Mixin(value = AdvCraftingCPULogic.class, remap = false)
public abstract class AdvCraftingCpuLogicBatchMixin {
    @Shadow
    private ExecutingCraftingJob job;

    @Shadow
    @Final
    private ListCraftingInventory inventory;

    @Shadow
    @Final
    AdvCraftingCPU cpu;

    @Unique
    @Nullable
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> thunderbolt$batchedByTask;

    @Unique
    private long thunderbolt$batchTick;

    @Unique
    private boolean thunderbolt$batchExhaustedThisTick;

    @WrapOperation(
            method = "tickCraftingLogic",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/pedroksl/advanced_ae/common/logic/AdvCraftingCPULogic;executeCrafting"
                            + "(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I"
            )
    )
    private int thunderbolt$wrapExecuteCrafting(AdvCraftingCPULogic self,
                                          int remainingOps,
                                          CraftingService craftingService,
                                          IEnergyService energyService,
                                          Level level,
                                          Operation<Integer> original) {
        long now = TickHandler.instance().getCurrentTick();
        var batchedByTask = thunderbolt$getBatchedByTask();
        if (now != thunderbolt$batchTick) {
            thunderbolt$batchTick = now;
            batchedByTask.clear();
            thunderbolt$batchExhaustedThisTick = false;
        }

        if (job == null || thunderbolt$batchExhaustedThisTick) {
            return original.call(self, remainingOps, craftingService, energyService, level);
        }

        var jobAccessor = (AaeExecutingCraftingJobAccessor) (Object) job;
        var timeTracker = (AaeElapsedTimeTrackerAccessor) jobAccessor.getTimeTracker();
        var batchResult = BatchExecutor.runBatchOnly(
                remainingOps,
                BatchCpuAccounting.Mode.LINEAR,
                craftingService,
                energyService,
                new DefaultBatchJobView(
                        level,
                        jobAccessor.getLink().getCraftingID(),
                        jobAccessor.getTasks(),
                        jobAccessor.getWaitingFor(),
                        task -> ((AaeTaskProgressAccessor) task).getValue(),
                        (task, value) -> ((AaeTaskProgressAccessor) task).setValue(value),
                        timeTracker,
                        (tracker, count, type) -> ((AaeElapsedTimeTrackerAccessor) tracker)
                                .invokeAddMaxItems(count, type)),
                inventory,
                batchedByTask,
                cpu::markDirty);

        if (batchResult.dispatchedCopies() > 0) {
            // AdvancedAE CPUs keep batch extraction/provider dispatch, but pay one operation per copy.
            // UNBOUNDED providers (such as creative item sources) still pay one operation per dispatch.
            return batchResult.consumedCpuOps();
        }

        // No batch-dispatchable task this tick (no batch provider / all full / out of material).
        // Game time is frozen within a tick, so capacity cannot recover; skip the per-round re-probe.
        thunderbolt$batchExhaustedThisTick = true;
        return original.call(self, remainingOps, craftingService, energyService, level);
    }

    @WrapOperation(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/me/service/CraftingService;getProviders"
                            + "(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;"
            )
    )
    private Iterable<ICraftingProvider> thunderbolt$filterBatched(CraftingService craftingService,
                                                            IPatternDetails details,
                                                            Operation<Iterable<ICraftingProvider>> original) {
        var raw = original.call(craftingService, details);
        var batchedByTask = thunderbolt$getBatchedByTask();
        if (batchedByTask.isEmpty()) return raw;
        var perTask = batchedByTask.get(details);
        if (perTask == null || perTask.isEmpty()) return raw;
        return new BatchProviderFilterIterable(raw, perTask);
    }

    @Unique
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> thunderbolt$getBatchedByTask() {
        if (this.thunderbolt$batchedByTask == null) {
            this.thunderbolt$batchedByTask = new HashMap<>();
        }
        return this.thunderbolt$batchedByTask;
    }
}
