package com.moakiee.thunderbolt.core.crafting.algorithm;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import net.minecraft.resources.ResourceLocation;

/** Bridges the algorithm selected on AE2's calculation thread to its confirmation menu. */
public final class CraftingAlgorithmCalculationStatus {
    private static final Map<ICraftingSimulationRequester, Status> BY_REQUESTER =
            new IdentityHashMap<>();
    private static final Map<Future<ICraftingPlan>, Status> BY_FUTURE =
            new IdentityHashMap<>();

    private CraftingAlgorithmCalculationStatus() {
    }

    public static Future<ICraftingPlan> track(
            ICraftingSimulationRequester requester,
            Supplier<Future<ICraftingPlan>> calculationStarter) {
        var status = new Status(requester);
        synchronized (CraftingAlgorithmCalculationStatus.class) {
            BY_REQUESTER.put(requester, status);
        }
        try {
            var future = calculationStarter.get();
            synchronized (CraftingAlgorithmCalculationStatus.class) {
                BY_FUTURE.put(future, status);
            }
            return future;
        } catch (RuntimeException | Error failure) {
            synchronized (CraftingAlgorithmCalculationStatus.class) {
                BY_REQUESTER.remove(requester, status);
            }
            throw failure;
        }
    }

    public static synchronized void select(
            ICraftingSimulationRequester requester, ResourceLocation algorithmId) {
        var status = BY_REQUESTER.get(requester);
        if (status != null) {
            status.algorithmId = algorithmId;
        }
    }

    @Nullable
    public static synchronized ResourceLocation selected(Future<ICraftingPlan> future) {
        var status = BY_FUTURE.get(future);
        return status == null ? null : status.algorithmId;
    }

    public static synchronized void forget(@Nullable Future<ICraftingPlan> future) {
        if (future == null) {
            return;
        }
        var status = BY_FUTURE.remove(future);
        if (status != null) {
            BY_REQUESTER.remove(status.requester, status);
        }
    }

    private static final class Status {
        private final ICraftingSimulationRequester requester;
        @Nullable
        private ResourceLocation algorithmId;

        private Status(ICraftingSimulationRequester requester) {
            this.requester = requester;
        }
    }
}
