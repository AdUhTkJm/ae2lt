package com.moakiee.thunderbolt.api.crafting.cpu;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridNodeService;

/** Grid-node service that dynamically exposes a non-vanilla crafting CPU cluster. */
public interface ExtendedCraftingCpuClusterProvider extends IGridNodeService {
    @Nullable
    ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster();
}
