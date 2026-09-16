package com.moakiee.thunderbolt.api.crafting.cpu;

import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;

/** Public adapter for crafting CPU implementations not backed by AE2's concrete cluster class. */
public interface ExtendedCraftingCpuCluster extends ICraftingCPU {
    boolean isActive();

    /** Player-configured priority used when automatically choosing between crafting CPUs. */
    default int getCpuPriority() {
        return 0;
    }

    default boolean isFastPlanningEnabled() {
        return false;
    }

    Collection<? extends ICraftingCPU> getActiveCpus();

    long tickCraftingLogic(IEnergyService energyService, ICraftingService craftingService);

    void addWaitingKeys(Set<AEKey> waitingKeys);

    long insert(AEKey what, long amount, Actionable mode);

    long getRequestedAmount(AEKey what);

    ICraftingSubmitResult submitJob(
            IGrid grid,
            ICraftingPlan plan,
            IActionSource src,
            @Nullable ICraftingRequester requester);

    default void prepareForCraftingService() {
    }

    default void restoreCraftingLinks(Consumer<ICraftingLink> consumer) {
    }

    /** Defaults to AE2's native plan contract; specialized CPUs opt in to other plan types. */
    default boolean canHandle(ICraftingPlan plan) {
        return plan instanceof CraftingPlan;
    }

    default boolean canBeAutoSelectedFor(IActionSource source) {
        return switch (getSelectionMode()) {
            case ANY -> true;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    default boolean isPreferredFor(IActionSource source) {
        return switch (getSelectionMode()) {
            case ANY -> false;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    default boolean consumeCpuListChanged() {
        return false;
    }

    default boolean containsCpu(ICraftingCPU cpu) {
        if (cpu == this) {
            return true;
        }
        for (var activeCpu : getActiveCpus()) {
            if (activeCpu == cpu) {
                return true;
            }
        }
        return false;
    }
}
