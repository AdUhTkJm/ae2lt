package com.moakiee.thunderbolt.core.crafting.batch;

public final class BatchCpuAccounting {
    private BatchCpuAccounting() {
    }

    public enum Mode {
        LINEAR,
        QUADRATIC,
        /** One successful provider call costs one dispatch, regardless of accepted copies. */
        SUCCESSFUL_DISPATCH
    }

    public static long maxCopiesForCpuOps(int cpuOps, Mode mode) {
        if (cpuOps <= 0) return 0;
        if (mode == Mode.LINEAR) return cpuOps;
        if (mode == Mode.SUCCESSFUL_DISPATCH) return Long.MAX_VALUE;
        return (long) cpuOps * cpuOps;
    }

    public static long maxCopiesForBatch(
            int remainingCpuOps, int maxBatchOps, long remainingCopies, Mode mode) {
        if (remainingCpuOps <= 0 || maxBatchOps <= 0 || remainingCopies <= 0L) return 0L;
        if (mode == Mode.SUCCESSFUL_DISPATCH) return remainingCopies;
        int batchOps = Math.min(remainingCpuOps, maxBatchOps);
        return Math.min(maxCopiesForCpuOps(batchOps, mode), remainingCopies);
    }

    public static int cpuOpsForCopies(int copies, Mode mode) {
        return cpuOpsForCopies((long) copies, mode);
    }

    public static int cpuOpsForCopies(long copies, Mode mode) {
        if (copies <= 0) return 0;
        if (mode == Mode.LINEAR) return copies >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) copies;
        if (mode == Mode.SUCCESSFUL_DISPATCH) return 1;
        return cpuOpsForCopies(copies);
    }

    public static long maxCopiesForCpuOps(int cpuOps) {
        if (cpuOps <= 0) return 0;
        return (long) cpuOps * cpuOps;
    }

    public static int cpuOpsForCopies(int copies) {
        return cpuOpsForCopies((long) copies);
    }

    public static int cpuOpsForCopies(long copies) {
        if (copies <= 0) return 0;
        long maxRepresentableCopies = (long) Integer.MAX_VALUE * Integer.MAX_VALUE;
        if (copies >= maxRepresentableCopies) return Integer.MAX_VALUE;

        long root = (long) Math.sqrt(copies);
        while (root * root < copies) {
            root++;
        }
        while (root > 0 && (root - 1) * (root - 1) >= copies) {
            root--;
        }
        return (int) root;
    }
}
