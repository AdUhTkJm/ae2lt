package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.api.crafting.batch.BatchDispatchMode;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.core.crafting.batch.BatchCopyLimitPattern;
import com.moakiee.thunderbolt.core.crafting.support.CraftingPatternDelegates;

public final class BatchExecutor {
    private static final Set<String> REPORTED_SHARED_SEMANTIC_MISMATCHES =
            ConcurrentHashMap.newKeySet();

    private BatchExecutor() {
    }

    /**
     * Patterns matching this rule are skipped by the batch dispatcher (they are handled by another
     * path). Decoupled from content: the host mod installs the rule during setup (e.g. to exclude
     * its host-specific pattern type). Defaults to "skip nothing" so the lib works standalone.
     */
    private static volatile Predicate<IPatternDetails> skipRule = details -> false;
    private static volatile Predicate<IPatternDetails> batchEligibleRule = details -> true;

    /** Adds a pattern skip rule. Registered rules are combined with logical OR. */
    public static synchronized void registerSkipRule(Predicate<IPatternDetails> rule) {
        Objects.requireNonNull(rule, "rule");
        var previous = skipRule;
        skipRule = details -> previous.test(details) || rule.test(details);
    }

    /**
     * Adds a pattern eligibility rule. Registered rules are combined with logical AND, so one
     * integration cannot silently relax another integration's restriction.
     */
    public static synchronized void registerBatchEligibilityRule(
            Predicate<IPatternDetails> rule) {
        Objects.requireNonNull(rule, "rule");
        var previous = batchEligibleRule;
        batchEligibleRule = details -> previous.test(details) && rule.test(details);
    }

    static boolean shouldSkip(IPatternDetails details) {
        return skipRule.test(details);
    }

    static boolean isBatchEligible(IPatternDetails details) {
        return batchEligibleRule.test(details);
    }

    public static BatchRunResult runBatchOnly(int remainingOps,
                                              BatchCpuAccounting.Mode accountingMode,
                                              CraftingService cs,
                                              IEnergyService es,
                                              BatchJobView job,
                                              ListCraftingInventory inv,
                                              Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
                                              Runnable markDirty) {
        return runBatchOnly(remainingOps, accountingMode, cs, es, job, inv,
                batchedByTask, markDirty, Map.of());
    }

    public static BatchRunResult runBatchOnly(int remainingOps,
                                              BatchCpuAccounting.Mode accountingMode,
                                              CraftingService cs,
                                              IEnergyService es,
                                              BatchJobView job,
                                              ListCraftingInventory inv,
                                              Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
                                              Runnable markDirty,
                                              Map<appeng.api.stacks.AEKey, Long> reservedStock) {
        return runBatchOnly(
                remainingOps,
                accountingMode,
                cs,
                es,
                job,
                inv,
                batchedByTask,
                markDirty,
                reservedStock,
                Integer.MAX_VALUE,
                Long.MAX_VALUE);
    }

    public static BatchRunResult runBatchOnly(int remainingOps,
                                              BatchCpuAccounting.Mode accountingMode,
                                              CraftingService cs,
                                              IEnergyService es,
                                              BatchJobView job,
                                              ListCraftingInventory inv,
                                              Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
                                              Runnable markDirty,
                                              Map<appeng.api.stacks.AEKey, Long> reservedStock,
                                              int maxBatchOps,
                                              long maxCopies) {
        return runBatchOnly(
                remainingOps,
                accountingMode,
                cs,
                es,
                job,
                inv,
                batchedByTask,
                markDirty,
                reservedStock,
                maxBatchOps,
                maxCopies,
                false,
                null);
    }

    public static BatchRunResult runBatchOnly(int remainingOps,
                                              BatchCpuAccounting.Mode accountingMode,
                                              CraftingService cs,
                                              IEnergyService es,
                                              BatchJobView job,
                                              ListCraftingInventory inv,
                                              Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
                                              Runnable markDirty,
                                              Map<appeng.api.stacks.AEKey, Long> reservedStock,
                                              int maxBatchOps,
                                              long maxCopies,
                                              boolean unboundedCpuBatch) {
        return runBatchOnly(
                remainingOps,
                accountingMode,
                cs,
                es,
                job,
                inv,
                batchedByTask,
                markDirty,
                reservedStock,
                maxBatchOps,
                maxCopies,
                unboundedCpuBatch,
                null);
    }

    public static BatchRunResult runBatchOnly(int remainingOps,
                                              BatchCpuAccounting.Mode accountingMode,
                                              CraftingService cs,
                                              IEnergyService es,
                                              BatchJobView job,
                                              ListCraftingInventory inv,
                                              Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batchedByTask,
                                              Runnable markDirty,
                                              Map<appeng.api.stacks.AEKey, Long> reservedStock,
                                              int maxBatchOps,
                                              long maxCopies,
                                              boolean unboundedCpuBatch,
                                              TickProviderDispatchSchedule dispatchSchedule) {
        if (job == null) return BatchRunResult.EMPTY;

        var taskIter = job.taskIterator();
        if (!taskIter.hasNext()) return BatchRunResult.EMPTY;

        long totalPushed = 0;
        int consumedOps = 0;
        int opsBudget = remainingOps;
        long copiesBudget = Math.max(0L, maxCopies);
        maxBatchOps = Math.max(1, maxBatchOps);
        if (opsBudget <= 0 || copiesBudget <= 0) return BatchRunResult.EMPTY;
        if (accountingMode == null) accountingMode = BatchCpuAccounting.Mode.LINEAR;
        boolean dirty = false;
        boolean sawBatchProvider = false;

        while (taskIter.hasNext()) {
            var task = taskIter.next();
            long taskValue = task.getValue();
            if (taskValue <= 0) {
                taskIter.remove();
                continue;
            }

            var details = task.details();
            var executionDetails = CraftingPatternDelegates.forBatchExecution(details);
            if (shouldSkip(executionDetails)) {
                continue;
            }
            if (!isBatchEligible(executionDetails)) {
                continue;
            }
            boolean hasSharedInputs = SharedBatchInputs.hasSharedInputs(details);

            var providerPattern = CraftingPatternDelegates.forProviderLookup(details);
            var perTaskBatched = batchedByTask.get(details);
            java.util.ArrayList<EligibleProvider> eligible = null;
            var providerCandidates = dispatchSchedule != null
                    ? dispatchSchedule.candidates(cs, providerPattern, providerPattern)
                    : cs.getProviders(providerPattern);
            for (var provider : providerCandidates) {
                if (!(provider instanceof IBatchCraftingProvider batch)) continue;
                sawBatchProvider = true;
                if (perTaskBatched != null && perTaskBatched.containsKey(provider)) continue;
                long capacity;
                BatchDispatchMode dispatchMode;
                try {
                    if (hasSharedInputs && !batch.supportsSharedBatchInputs()) continue;
                    capacity = batch.getBatchCapacity(executionDetails);
                    dispatchMode = batch.getBatchDispatchMode(executionDetails);
                } catch (Throwable t) {
                    appeng.core.AELog.warn("[thunderbolt] IBatchCraftingProvider %s threw while reporting capacity; blocking this pattern for the current tick. %s",
                            batch, t);
                    if (dispatchSchedule != null) {
                        dispatchSchedule.recordFailure(providerPattern, provider);
                    }
                    continue;
                }
                // Capacity has a three-state meaning:
                //   0: unavailable/busy (the ordinary path independently observes isBusy());
                //   1: ordinary single-copy provider, let the CPU's bulk-extract/single-push
                //      path handle it without paying BatchExecutor's per-copy bookkeeping;
                //  >1: a real batch candidate.
                //
                // In particular, providers with a disabled batch feature commonly report 1.
                // Treating that as a one-copy batch would bypass pushBulkForTask and repeat batch
                // extraction, template cloning, power simulation and output registration for
                // every physical dispatch.
                if (!usesBatchPath(capacity)) continue;
                if (dispatchMode == null) {
                    dispatchMode = BatchDispatchMode.NORMAL;
                }
                if (eligible == null) {
                    eligible = new java.util.ArrayList<>();
                }
                eligible.add(new EligibleProvider(batch, provider, capacity, dispatchMode));
            }
            if (eligible == null) continue;

            if (hasSharedInputs && eligible.size() > 1) {
                // One reusable seed cannot be in multiple executing providers simultaneously.
                eligible.sort(java.util.Comparator
                        .comparing((EligibleProvider provider) -> provider.mode() != BatchDispatchMode.UNBOUNDED)
                        .thenComparing(EligibleProvider::capacity, java.util.Comparator.reverseOrder()));
                eligible.subList(1, eligible.size()).clear();
            }

            boolean hasUnboundedProvider = eligible.stream()
                    .anyMatch(provider -> provider.mode() == BatchDispatchMode.UNBOUNDED);
            long availableBatchCapacity = 0;
            for (var provider : eligible) {
                availableBatchCapacity = saturatingAdd(availableBatchCapacity, provider.capacity());
            }
            if (availableBatchCapacity <= 0) continue;

            // Legacy-unbounded providers get first access to the current CPU copy budget. They do
            // not bypass copiesBudget. Normal providers retain the smaller-first balancing order.
            eligible.sort(java.util.Comparator
                    .comparing((EligibleProvider provider) -> provider.mode() != BatchDispatchMode.UNBOUNDED)
                    .thenComparingLong(EligibleProvider::capacity));

            long copyBudget = hasUnboundedProvider
                    || unboundedCpuBatch
                    || accountingMode == BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH
                    ? copiesBudget
                    : BatchCpuAccounting.maxCopiesForBatch(
                            opsBudget, maxBatchOps, copiesBudget, accountingMode);
            if (copyBudget <= 0) {
                if (dirty) markDirty.run();
                return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
            }

            long budget = Math.min(Math.min(taskValue, availableBatchCapacity), copyBudget);
            if (details instanceof BatchCopyLimitPattern limited) {
                budget = Math.min(budget, Math.max(1L, limited.maxBatchCopies()));
            }
            if (budget <= 0) continue;

            var result = ParallelBatchCpuHelper.bulkExtract(
                    details, inv, budget, true, reservedStock, job.level());
            if (result == null) {
                continue;
            }
            if (result.hasSharedInputs() && !hasSharedInputs) {
                eligible.removeIf(provider -> !provider.provider().supportsSharedBatchInputs());
                if (eligible.isEmpty()) {
                    ParallelBatchCpuHelper.reinject(result, result.actualCopies, inv);
                    continue;
                }
                if (eligible.size() > 1) {
                    eligible.sort(java.util.Comparator
                            .comparing((EligibleProvider provider) -> provider.mode() != BatchDispatchMode.UNBOUNDED)
                            .thenComparing(EligibleProvider::capacity, java.util.Comparator.reverseOrder()));
                    eligible.subList(1, eligible.size()).clear();
                }
            }
            if (!sharedBatchSemanticsMatch(details, executionDetails, result)) {
                ParallelBatchCpuHelper.reinject(result, result.actualCopies, inv);
                reportSharedSemanticMismatch(details, executionDetails, eligible);
                continue;
            }

            long realCraft = result.actualCopies;
            double powerForReal = CraftingCpuHelper.calculatePatternPower(result.scaledInputs);
            double powerOne = realCraft > 0 ? powerForReal / realCraft : 0.0D;
            double availablePower = es.extractAEPower(powerForReal, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (availablePower < powerForReal - 0.01D) {
                long affordable = powerOne > 0.0D ? floorToLong(availablePower / powerOne) : 0L;
                if (affordable <= 0) {
                    ParallelBatchCpuHelper.reinject(result, realCraft, inv);
                    if (dirty) markDirty.run();
                    return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                }
                long scaleDown = realCraft - affordable;
                if (scaleDown > 0) {
                    ParallelBatchCpuHelper.reinject(result, scaleDown, inv);
                    realCraft = affordable;
                }
            }

            long initialRealCraft = realCraft;
            long leftover = realCraft;
            KeyCounter[] oneCopy = ParallelBatchCpuHelper.cloneSingleCopy(result);

            for (int i = 0; i < eligible.size() && leftover > 0; i++) {
                var eligibleProvider = eligible.get(i);
                var batch = eligibleProvider.provider();
                boolean unbounded = unboundedCpuBatch
                        || eligibleProvider.mode() == BatchDispatchMode.UNBOUNDED;
                long sliceCap = unbounded
                        || accountingMode == BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH
                        ? copiesBudget
                        : BatchCpuAccounting.maxCopiesForBatch(
                                opsBudget, maxBatchOps, copiesBudget, accountingMode);
                if (sliceCap <= 0) break;
                long slice;
                if (unbounded) {
                    slice = leftover;
                } else {
                    int remainingProviders = eligible.size() - i;
                    slice = Math.max(1L, leftover / remainingProviders);
                }
                slice = Math.min(slice, leftover);
                slice = Math.min(slice, sliceCap);
                slice = Math.min(slice, eligibleProvider.capacity());

                long subLeftover;
                try {
                    subLeftover = batch.pushBatch(executionDetails, oneCopy, slice, job);
                } catch (Throwable t) {
                    appeng.core.AELog.warn("[thunderbolt] IBatchCraftingProvider %s threw during pushBatch; treating as full leftover. %s",
                            batch, t);
                    subLeftover = slice;
                }
                if (subLeftover < 0 || subLeftover > slice) {
                    appeng.core.AELog.warn("[thunderbolt] IBatchCraftingProvider %s returned out-of-range leftover %d for slice=%d; treating as full leftover.",
                            batch, subLeftover, slice);
                    subLeftover = slice;
                }

                long dispatched = slice - subLeftover;
                if (dispatched <= 0) {
                    if (dispatchSchedule != null) {
                        dispatchSchedule.recordFailure(providerPattern, eligibleProvider.identity());
                    }
                    continue;
                }
                if (dispatchSchedule != null) {
                    dispatchSchedule.recordSuccess(providerPattern, eligibleProvider.identity());
                }

                ParallelBatchCpuHelper.markDispatched(result, dispatched);
                es.extractAEPower(powerOne * dispatched, Actionable.MODULATE, PowerMultiplier.CONFIG);
                ParallelBatchCpuHelper.registerExpectedOutputs(job, details, result, dispatched);
                dirty = true;

                int opsCost = unbounded
                        || accountingMode == BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH
                        ? 1
                        : BatchCpuAccounting.cpuOpsForCopies(dispatched, accountingMode);
                consumedOps += opsCost;
                opsBudget -= opsCost;

                long newValue = task.getValue() - dispatched;
                task.setValue(newValue);
                totalPushed = saturatingAdd(totalPushed, dispatched);
                copiesBudget -= dispatched;
                leftover -= dispatched;

                if (initialRealCraft > 1) {
                    if (perTaskBatched == null) {
                        perTaskBatched = batchedByTask.computeIfAbsent(details, key -> new IdentityHashMap<>());
                    }
                    perTaskBatched.put(eligibleProvider.identity(), Boolean.TRUE);
                }

                if (newValue <= 0) {
                    taskIter.remove();
                    if (leftover > 0) {
                        ParallelBatchCpuHelper.reinject(result, leftover, inv);
                        leftover = 0;
                    }
                    if (opsBudget <= 0 || copiesBudget <= 0) {
                        if (dirty) markDirty.run();
                        return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                    }
                    break;
                }

                if (opsBudget <= 0 || copiesBudget <= 0) {
                    if (leftover > 0) {
                        ParallelBatchCpuHelper.reinject(result, leftover, inv);
                        leftover = 0;
                    }
                    if (dirty) markDirty.run();
                    return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
                }
            }

            if (leftover > 0) {
                ParallelBatchCpuHelper.reinject(result, leftover, inv);
            }
        }

        if (dirty) markDirty.run();
        return new BatchRunResult(totalPushed, consumedOps, sawBatchProvider);
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    static boolean sharedBatchSemanticsMatch(
            IPatternDetails taskDetails,
            IPatternDetails executionDetails,
            ParallelBatchCpuHelper.BulkResult result) {
        if (!result.hasSharedInputs()) return true;

        for (int slot = 0; slot < result.sharedPerBatch.length; slot++) {
            for (var entry : result.sharedPerBatch[slot]) {
                if (!SharedBatchInputs.isSharedInput(
                        executionDetails, slot, entry.getKey())) {
                    return false;
                }
            }
        }

        var taskOutputs = outputAmounts(taskDetails);
        var executionOutputs = outputAmounts(executionDetails);
        if (!taskOutputs.equals(executionOutputs)) return false;

        for (var output : taskOutputs.entrySet()) {
            long taskShared = sharedOutputAmount(
                    taskDetails, output.getKey(), output.getValue());
            long executionShared = sharedOutputAmount(
                    executionDetails, output.getKey(), output.getValue());
            if (taskShared != executionShared) return false;
        }
        return true;
    }

    private static Map<AEKey, Long> outputAmounts(IPatternDetails details) {
        var result = new HashMap<AEKey, Long>();
        for (var output : details.getOutputs()) {
            if (output.what() != null && output.amount() > 0) {
                result.merge(output.what(), output.amount(), BatchExecutor::saturatingAdd);
            }
        }
        return result;
    }

    private static long sharedOutputAmount(
            IPatternDetails details, AEKey outputKey, long totalOutput) {
        if (!(details instanceof SharedBatchInputPattern shared)) return 0L;
        return Math.min(totalOutput, Math.max(0L, shared.sharedBatchOutputAmount(outputKey)));
    }

    private static void reportSharedSemanticMismatch(
            IPatternDetails taskDetails,
            IPatternDetails executionDetails,
            Iterable<EligibleProvider> providers) {
        var providerTypes = new java.util.TreeSet<String>();
        for (var provider : providers) {
            providerTypes.add(provider.identity().getClass().getName());
        }
        var key = taskDetails.getClass().getName() + " -> "
                + executionDetails.getClass().getName() + " -> " + providerTypes;
        if (REPORTED_SHARED_SEMANTIC_MISMATCHES.add(key)) {
            appeng.core.AELog.warn(
                    "[thunderbolt] Blocking shared-input batch because task, execution and provider semantics disagree. task=%s execution=%s providers=%s",
                    taskDetails.getClass().getName(),
                    executionDetails.getClass().getName(),
                    providerTypes);
        }
    }

    private static long floorToLong(double value) {
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return value <= 0.0D ? 0L : (long) Math.floor(value);
    }

    static boolean usesBatchPath(long capacity) {
        return capacity > 1L;
    }

    private record EligibleProvider(IBatchCraftingProvider provider, ICraftingProvider identity,
                                    long capacity, BatchDispatchMode mode) {
    }

    public record BatchRunResult(long dispatchedCopies, int consumedCpuOps, boolean sawBatchProvider) {
        public static final BatchRunResult EMPTY = new BatchRunResult(0L, 0, false);

        public boolean shouldRetryBatchThisTick() {
            return dispatchedCopies > 0;
        }
    }
}
