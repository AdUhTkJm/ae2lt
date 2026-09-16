package com.moakiee.thunderbolt.core.crafting.loop;

import com.moakiee.thunderbolt.api.crafting.cpu.ExtendedCraftingCpuClusterHost;

/** Pattern metadata restricting a generated plan to compatible extended crafting CPUs. */
public interface CraftingCpuRestrictedPattern {

    boolean acceptsCraftingCpu(ExtendedCraftingCpuClusterHost host);
}
