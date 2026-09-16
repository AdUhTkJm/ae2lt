package com.moakiee.thunderbolt.core.crafting.cpu;

/** Shared automatic ordering for AE2 concrete CPUs and Thunderbolt extended CPUs. */
public final class CraftingCpuSelectionOrder {
    public static int compare(
            int firstPriority,
            boolean firstPreferred,
            int firstCoProcessors,
            long firstAvailableStorage,
            int secondPriority,
            boolean secondPreferred,
            int secondCoProcessors,
            long secondAvailableStorage,
            boolean prioritizePower) {
        int priorityOrder = Integer.compare(secondPriority, firstPriority);
        if (priorityOrder != 0) {
            return priorityOrder;
        }
        if (firstPreferred != secondPreferred) {
            return Boolean.compare(secondPreferred, firstPreferred);
        }
        int coProcessorOrder = prioritizePower
                ? Integer.compare(secondCoProcessors, firstCoProcessors)
                : Integer.compare(firstCoProcessors, secondCoProcessors);
        if (coProcessorOrder != 0) {
            return coProcessorOrder;
        }
        return Long.compare(firstAvailableStorage, secondAvailableStorage);
    }

    private CraftingCpuSelectionOrder() {
    }
}
