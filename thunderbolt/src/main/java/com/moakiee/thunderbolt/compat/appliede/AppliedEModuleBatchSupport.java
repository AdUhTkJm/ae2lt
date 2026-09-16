package com.moakiee.thunderbolt.compat.appliede;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;

/** Aggregates into AppliedE's existing output queue; EMC is paid by the CPU's batch extraction. */
public final class AppliedEModuleBatchSupport {
    private AppliedEModuleBatchSupport() {
    }

    public static long capacity(@Nullable GenericStack output, Object2LongMap<AEKey> pending) {
        if (output == null || output.amount() <= 0L) return 0L;
        long held = pending.getLong(output.what());
        if (held < 0L) return 0L;
        return (Long.MAX_VALUE - held) / output.amount();
    }

    /** Returns unaccepted copies, with one queue update regardless of the batch size. */
    public static long enqueue(
            @Nullable GenericStack output, Object2LongMap<AEKey> pending, long copies) {
        if (copies <= 0L) return 0L;
        long accepted = Math.min(copies, capacity(output, pending));
        if (accepted > 0L) {
            pending.put(output.what(), pending.getLong(output.what()) + output.amount() * accepted);
        }
        return copies - accepted;
    }
}
