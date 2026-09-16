package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.core.crafting.batch.BatchExecutor;
import com.moakiee.thunderbolt.core.crafting.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.core.crafting.batch.BatchProviderFilterIterable;
import com.moakiee.thunderbolt.core.crafting.batch.DefaultBatchJobView;

/**
 * Batches identical pattern firings on the vanilla crafting CPU within a tick.
 *
 * <p><b>TODO (fuzzy substitution reconciliation — execution side).</b> The fast planner
 * ({@code FastCraftingPlanner}) commits to a concrete substitute for each hard-fuzzy input slot and
 * charges that exact key as "used", but AE2's fuzzy matcher resolves the slot at extraction time and may
 * pull a different acceptable variant (different NBT/damage, or another tag member). That is an
 * execution-time issue, not a planning one (the plan is still mass-balanced for the key it charged), so
 * the fix belongs here on the executing CPU, which sees the real extraction: when a fuzzy slot resolves
 * to a stack other than the one the plan charged, reconcile against what was actually consumed rather than
 * trusting the planned key. See {@code FastCraftingPlanner}'s "Execution-time contract" note.
 */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicBatchMixin {
    @Shadow
    private ExecutingCraftingJob job;

    @Shadow
    @Final
    CraftingCPUCluster cluster;

    @Shadow
    public abstract ListCraftingInventory getInventory();

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
                    target = "Lappeng/crafting/execution/CraftingCpuLogic;executeCrafting"
                            + "(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I"
            )
    )
    private int thunderbolt$wrapExecuteCrafting(CraftingCpuLogic self,
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

        var jobAccessor = (ExecutingCraftingJobAccessor) job;
        var timeTracker = (ElapsedTimeTrackerAccessor) jobAccessor.getTimeTracker();
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
                        task -> ((TaskProgressAccessor) task).getValue(),
                        (task, value) -> ((TaskProgressAccessor) task).setValue(value),
                        timeTracker,
                        (tracker, count, type) -> ((ElapsedTimeTrackerAccessor) tracker)
                                .invokeAddMaxItems(count, type)),
                getInventory(),
                batchedByTask,
                cluster::markDirty);

        if (batchResult.dispatchedCopies() > 0) {
            // Vanilla CPUs keep batch extraction/provider dispatch, but pay one operation per copy.
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
