package com.moakiee.thunderbolt.api.crafting.cpu;

/** Grid-node owner that exposes a non-vanilla crafting CPU cluster to Thunderbolt. */
public interface ExtendedCraftingCpuClusterHost extends ExtendedCraftingCpuClusterProvider {
    @Override
    ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster();
}
