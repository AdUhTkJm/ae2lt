package com.moakiee.thunderbolt.core.crafting.batch;

import com.moakiee.thunderbolt.core.crafting.batch.SharedBatchInputPattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.inv.ListCraftingInventory;

import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;

public final class ParallelBatchCpuHelper {
    private ParallelBatchCpuHelper() {
    }

    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft) {
        return bulkExtract(details, inv, maxCraft, true, Map.of());
    }

    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft,
                                         boolean allowSharedInputs, Map<AEKey, Long> reservedStock) {
        if (maxCraft <= 0) return null;

        var inputs = details.getInputs();
        int slots = inputs.length;
        var chosenKeys = new AEKey[slots];
        var units = new long[slots];
        var available = new long[slots];
        var shared = new boolean[slots];
        var reserved = reservedStock != null ? reservedStock : Map.<AEKey, Long>of();

        for (int slot = 0; slot < slots; slot++) {
            var input = inputs[slot];
            var possibles = input.getPossibleInputs();
            AEKey bestKey = null;
            long bestUnits = 0;
            long bestAvailable = 0;
            long bestCopies = 0;
            boolean bestShared = false;
            for (var possible : possibles) {
                if (possible.what() == null) continue;
                long perCopy = saturatingMultiply(possible.amount(), input.getMultiplier());
                if (perCopy <= 0) continue;
                long inInventory = Math.max(0L,
                        inv.extract(possible.what(), Long.MAX_VALUE, Actionable.SIMULATE)
                                - Math.max(0L, reserved.getOrDefault(possible.what(), 0L)));
                boolean isShared = allowSharedInputs
                        && SharedBatchInputs.isSharedInput(details, slot, possible.what());
                long copies = isShared ? (inInventory >= perCopy ? maxCraft : 0L) : inInventory / perCopy;
                if (copies > bestCopies) {
                    bestKey = possible.what();
                    bestUnits = perCopy;
                    bestAvailable = inInventory;
                    bestCopies = copies;
                    bestShared = isShared;
                    if (copies >= maxCraft) break;
                }
            }
            if (bestKey == null || bestCopies <= 0) return null;
            chosenKeys[slot] = bestKey;
            units[slot] = bestUnits;
            available[slot] = bestAvailable;
            shared[slot] = bestShared;
        }

        var fixedByKey = new HashMap<AEKey, Long>(slots * 2);
        var variableByKey = new HashMap<AEKey, Long>(slots * 2);
        var availableByKey = new HashMap<AEKey, Long>(slots * 2);
        for (int slot = 0; slot < slots; slot++) {
            availableByKey.put(chosenKeys[slot], available[slot]);
            (shared[slot] ? fixedByKey : variableByKey)
                    .merge(chosenKeys[slot], units[slot], ParallelBatchCpuHelper::saturatingAdd);
        }

        long actual = maxCraft;
        for (var entry : availableByKey.entrySet()) {
            long fixed = fixedByKey.getOrDefault(entry.getKey(), 0L);
            long variable = variableByKey.getOrDefault(entry.getKey(), 0L);
            if (fixed > entry.getValue()) return null;
            if (variable > 0) actual = Math.min(actual, (entry.getValue() - fixed) / variable);
            if (actual <= 0) return null;
        }
        var extractedByKey = new HashMap<AEKey, Long>(availableByKey.size() * 2);
        for (var entry : availableByKey.entrySet()) {
            long need = saturatingAdd(
                    fixedByKey.getOrDefault(entry.getKey(), 0L),
                    saturatingMultiply(variableByKey.getOrDefault(entry.getKey(), 0L), actual));
            long got = inv.extract(entry.getKey(), need, Actionable.MODULATE);
            extractedByKey.put(entry.getKey(), got);
            if (got < need) {
                for (var rollback : extractedByKey.entrySet()) {
                    if (rollback.getValue() > 0) {
                        inv.insert(rollback.getKey(), rollback.getValue(), Actionable.MODULATE);
                    }
                }
                return null;
            }
        }

        var scaled = new KeyCounter[slots];
        for (int slot = 0; slot < slots; slot++) {
            scaled[slot] = new KeyCounter();
            long amount = shared[slot] ? units[slot] : saturatingMultiply(units[slot], actual);
            if (amount > 0) scaled[slot].add(chosenKeys[slot], amount);
        }
        return new BulkResult(scaled, actual, chosenKeys, units, shared);
    }

    /**
     * Resolves the first concrete copy with AE2's native substitution rules, then scales only that
     * homogeneous input set. Different component variants therefore become separate batches.
     */
    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft,
                                         boolean allowSharedInputs, Map<AEKey, Long> reservedStock,
                                         Level level) {
        if (maxCraft <= 0) return null;

        var guardedInventory = new ReservedInventory(inv, reservedStock);
        var resolved = extractOneCopy(details, guardedInventory, allowSharedInputs, level);
        if (resolved == null) return null;

        int slots = resolved.inputs.length;
        var scalablePerCopy = new KeyCounter[slots];
        var sharedPerBatch = new KeyCounter[slots];
        var scalableDemand = new HashMap<AEKey, Long>(slots * 2);
        for (int slot = 0; slot < slots; slot++) {
            scalablePerCopy[slot] = new KeyCounter();
            sharedPerBatch[slot] = new KeyCounter();
            for (var entry : resolved.inputs[slot]) {
                boolean shared = allowSharedInputs
                        && SharedBatchInputs.isSharedInput(details, slot, entry.getKey());
                var target = shared ? sharedPerBatch[slot] : scalablePerCopy[slot];
                target.add(entry.getKey(), entry.getLongValue());
                if (!shared) {
                    scalableDemand.merge(
                            entry.getKey(), entry.getLongValue(), ParallelBatchCpuHelper::saturatingAdd);
                }
            }
        }

        long additionalCopies = maxCraft - 1;
        for (var entry : scalableDemand.entrySet()) {
            long available = guardedInventory.extract(entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
            additionalCopies = Math.min(additionalCopies, available / entry.getValue());
        }

        var additionalExtracted = new HashMap<AEKey, Long>(scalableDemand.size() * 2);
        if (additionalCopies > 0) {
            for (var entry : scalableDemand.entrySet()) {
                long needed = saturatingMultiply(entry.getValue(), additionalCopies);
                long extracted = guardedInventory.extract(entry.getKey(), needed, Actionable.MODULATE);
                additionalExtracted.put(entry.getKey(), extracted);
                if (extracted < needed) {
                    for (var rollback : additionalExtracted.entrySet()) {
                        guardedInventory.insert(rollback.getKey(), rollback.getValue(), Actionable.MODULATE);
                    }
                    CraftingCpuHelper.reinjectPatternInputs(guardedInventory, resolved.inputs);
                    return null;
                }
            }
        }

        long actualCopies = additionalCopies + 1;
        var scaled = new KeyCounter[slots];
        for (int slot = 0; slot < slots; slot++) {
            scaled[slot] = new KeyCounter();
            scaled[slot].addAll(sharedPerBatch[slot]);
            addScaled(scaled[slot], scalablePerCopy[slot], actualCopies);
        }
        return new BulkResult(
                scaled, actualCopies, scalablePerCopy, sharedPerBatch, resolved.remainders);
    }

    @Nullable
    private static ResolvedCopy extractOneCopy(IPatternDetails details,
                                               ICraftingInventory inventory,
                                               boolean allowSharedInputs,
                                               Level level) {
        var inputs = details.getInputs();
        var resolved = new KeyCounter[inputs.length];
        var remainders = new ArrayList<RemainderSpec>();
        for (int slot = 0; slot < inputs.length; slot++) {
            var input = inputs[slot];
            var holder = resolved[slot] = new KeyCounter();
            long remainingMultiplier = input.getMultiplier();
            for (var template : CraftingCpuHelper.getValidItemTemplates(inventory, input, level)) {
                long extracted = CraftingCpuHelper.extractTemplates(
                        inventory, template, remainingMultiplier);
                if (extracted <= 0) continue;

                holder.add(template.key(), saturatingMultiply(extracted, template.amount()));
                var remaining = input.getRemainingKey(template.key());
                if (remaining != null) {
                    boolean shared = allowSharedInputs
                            && SharedBatchInputs.isSharedInput(details, slot, template.key());
                    remainders.add(new RemainderSpec(remaining, extracted, shared));
                }
                remainingMultiplier -= extracted;
                if (remainingMultiplier == 0) break;
            }
            if (remainingMultiplier > 0) {
                CraftingCpuHelper.reinjectPatternInputs(inventory, resolved);
                return null;
            }
        }
        return new ResolvedCopy(resolved, List.copyOf(remainders));
    }

    public static void reinject(BulkResult result, long leftoverCopies, ListCraftingInventory inv) {
        if (leftoverCopies <= 0) return;
        long returnedCopies = Math.min(leftoverCopies, result.remainingCopies);
        for (int slot = 0; slot < result.scaledInputs.length; slot++) {
            for (var entry : result.scalablePerCopy[slot]) {
                long amount = saturatingMultiply(entry.getLongValue(), returnedCopies);
                if (amount > 0) {
                    inv.insert(entry.getKey(), amount, Actionable.MODULATE);
                    result.scaledInputs[slot].remove(entry.getKey(), amount);
                }
            }
        }
        result.remainingCopies -= returnedCopies;
        if (result.remainingCopies == 0 && !result.sharedDispatched) result.reinjectShared(inv);
    }

    public static void registerExpectedOutputs(BatchJobView job, IPatternDetails details,
                                               BulkResult result, long dispatched) {
        if (dispatched <= 0) return;
        registerPatternOutputs(job, details, dispatched, result.hasSharedInputs());
        if (result.remainders != null) {
            for (var remainder : result.remainders) {
                long copies = remainder.shared ? 1L : dispatched;
                long count = saturatingMultiply(remainder.count, copies);
                job.insertWaitingFor(remainder.key, count);
                job.addContainerMaxItems(count, remainder.key.getType());
            }
        } else {
            registerLegacyRemainders(
                    job, details, result.keys, result.sharedInputs, dispatched);
        }
    }

    public static void registerExpectedOutputs(BatchJobView job, IPatternDetails details,
                                               AEKey[] chosenKeys, long dispatched) {
        if (dispatched <= 0) return;
        boolean sharedBatch = false;
        if (chosenKeys != null) {
            for (int slot = 0; slot < chosenKeys.length; slot++) {
                if (SharedBatchInputs.isSharedInput(details, slot, chosenKeys[slot])) {
                    sharedBatch = true;
                    break;
                }
            }
        }
        registerPatternOutputs(job, details, dispatched, sharedBatch);
        registerLegacyRemainders(job, details, chosenKeys, null, dispatched);
    }

    private static void registerPatternOutputs(
            BatchJobView job, IPatternDetails details, long dispatched, boolean sharedBatch) {
        var sharedPattern = sharedBatch && details instanceof SharedBatchInputPattern pattern
                ? pattern : null;
        var sharedOutputsLeft = new HashMap<AEKey, Long>();
        for (var output : details.getOutputs()) {
            long sharedAmount = 0L;
            if (sharedPattern != null) {
                long remainingShared = sharedOutputsLeft.computeIfAbsent(
                        output.what(), sharedPattern::sharedBatchOutputAmount);
                sharedAmount = Math.min(output.amount(), Math.max(0L, remainingShared));
                sharedOutputsLeft.put(output.what(), remainingShared - sharedAmount);
            }
            long scalable = Math.max(0L, output.amount() - sharedAmount);
            job.insertWaitingFor(output.what(), saturatingAdd(
                    sharedAmount, saturatingMultiply(scalable, dispatched)));
        }
    }

    private static void registerLegacyRemainders(BatchJobView job, IPatternDetails details,
                                                 AEKey[] chosenKeys, boolean[] shared, long dispatched) {
        var inputs = details.getInputs();
        for (int slot = 0; slot < inputs.length; slot++) {
            var input = inputs[slot];
            var possibles = input.getPossibleInputs();
            if (possibles.length == 0) continue;
            AEKey consumed = chosenKeys != null && slot < chosenKeys.length && chosenKeys[slot] != null
                    ? chosenKeys[slot] : possibles[0].what();
            AEKey remaining = input.getRemainingKey(consumed);
            if (remaining != null) {
                boolean sharedInput = shared != null && slot < shared.length
                        ? shared[slot]
                        : SharedBatchInputs.isSharedInput(details, slot, consumed);
                long copies = sharedInput ? 1L : dispatched;
                // CraftingCpuHelper registers one remainder per completed template operation;
                // the possible stack's physical amount only affects extraction, not return count.
                long perCopy = input.getMultiplier();
                long count = saturatingMultiply(perCopy, copies);
                job.insertWaitingFor(remaining, count);
                job.addContainerMaxItems(count, remaining.getType());
            }
        }
    }

    public static KeyCounter[] cloneSingleCopy(BulkResult result) {
        return copySlice(result, 1);
    }

    public static KeyCounter[] copySlice(BulkResult result, long sliceCount) {
        var slice = new KeyCounter[result.scaledInputs.length];
        for (int slot = 0; slot < slice.length; slot++) {
            slice[slot] = new KeyCounter();
            slice[slot].addAll(result.sharedPerBatch[slot]);
            addScaled(slice[slot], result.scalablePerCopy[slot], Math.max(0, sliceCount));
        }
        return slice;
    }

    public static void markDispatched(BulkResult result, long dispatchedCopies) {
        if (dispatchedCopies <= 0) return;
        long accepted = Math.min(dispatchedCopies, result.remainingCopies);
        for (int slot = 0; slot < result.scaledInputs.length; slot++) {
            for (var entry : result.scalablePerCopy[slot]) {
                long amount = saturatingMultiply(entry.getLongValue(), accepted);
                if (amount > 0) {
                    result.scaledInputs[slot].remove(entry.getKey(), amount);
                }
            }
            if (!result.sharedDispatched) {
                for (var entry : result.sharedPerBatch[slot]) {
                    result.scaledInputs[slot].remove(entry.getKey(), entry.getLongValue());
                }
            }
        }
        result.sharedDispatched = true;
        result.remainingCopies -= accepted;
    }

    public static final class BulkResult {
        public final KeyCounter[] scaledInputs;
        public final long actualCopies;
        final AEKey[] keys;
        final long[] units;
        final boolean[] sharedInputs;
        final KeyCounter[] scalablePerCopy;
        final KeyCounter[] sharedPerBatch;
        @Nullable
        final List<RemainderSpec> remainders;
        long remainingCopies;
        boolean sharedDispatched;

        public BulkResult(KeyCounter[] scaledInputs, long actualCopies, AEKey[] keys,
                          long[] units, boolean[] sharedInputs) {
            this.scaledInputs = scaledInputs;
            this.actualCopies = actualCopies;
            this.keys = Arrays.copyOf(keys, keys.length);
            this.units = Arrays.copyOf(units, units.length);
            this.sharedInputs = Arrays.copyOf(sharedInputs, sharedInputs.length);
            this.scalablePerCopy = new KeyCounter[scaledInputs.length];
            this.sharedPerBatch = new KeyCounter[scaledInputs.length];
            for (int slot = 0; slot < scaledInputs.length; slot++) {
                this.scalablePerCopy[slot] = new KeyCounter();
                this.sharedPerBatch[slot] = new KeyCounter();
                if (keys[slot] != null && units[slot] > 0) {
                    (sharedInputs[slot] ? this.sharedPerBatch[slot] : this.scalablePerCopy[slot])
                            .add(keys[slot], units[slot]);
                }
            }
            this.remainders = null;
            this.remainingCopies = actualCopies;
        }

        private BulkResult(KeyCounter[] scaledInputs, long actualCopies,
                           KeyCounter[] scalablePerCopy, KeyCounter[] sharedPerBatch,
                           List<RemainderSpec> remainders) {
            this.scaledInputs = scaledInputs;
            this.actualCopies = actualCopies;
            this.keys = new AEKey[scaledInputs.length];
            this.units = new long[scaledInputs.length];
            this.sharedInputs = new boolean[scaledInputs.length];
            this.scalablePerCopy = scalablePerCopy;
            this.sharedPerBatch = sharedPerBatch;
            this.remainders = remainders;
            this.remainingCopies = actualCopies;
        }

        public boolean hasSharedInputs() {
            for (var counter : sharedPerBatch) {
                if (counter.iterator().hasNext()) return true;
            }
            return false;
        }

        private void reinjectShared(ListCraftingInventory inv) {
            for (int slot = 0; slot < scaledInputs.length; slot++) {
                for (var entry : sharedPerBatch[slot]) {
                    inv.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
                    scaledInputs[slot].remove(entry.getKey(), entry.getLongValue());
                }
            }
        }
    }

    private static void addScaled(KeyCounter target, KeyCounter source, long scale) {
        if (scale <= 0) return;
        for (var entry : source) {
            target.add(entry.getKey(), saturatingMultiply(entry.getLongValue(), scale));
        }
    }

    private record ResolvedCopy(KeyCounter[] inputs, List<RemainderSpec> remainders) {
    }

    private record RemainderSpec(AEKey key, long count, boolean shared) {
    }

    private static final class ReservedInventory implements ICraftingInventory {
        private final ListCraftingInventory delegate;
        private final Map<AEKey, Long> reserved;

        private ReservedInventory(ListCraftingInventory delegate, Map<AEKey, Long> reserved) {
            this.delegate = delegate;
            this.reserved = reserved != null ? reserved : Map.of();
        }

        @Override
        public void insert(AEKey what, long amount, Actionable mode) {
            delegate.insert(what, amount, mode);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode) {
            long available = delegate.extract(what, Long.MAX_VALUE, Actionable.SIMULATE);
            long protectedAmount = Math.max(0L, reserved.getOrDefault(what, 0L));
            long extractable = Math.max(0L, available - protectedAmount);
            long allowed = Math.min(Math.max(0L, amount), extractable);
            return mode == Actionable.SIMULATE
                    ? allowed : delegate.extract(what, allowed, Actionable.MODULATE);
        }

        @Override
        public Iterable<AEKey> findFuzzyTemplates(AEKey input) {
            return delegate.findFuzzyTemplates(input);
        }
    }

    private static long saturatingAdd(long left, long right) {
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
