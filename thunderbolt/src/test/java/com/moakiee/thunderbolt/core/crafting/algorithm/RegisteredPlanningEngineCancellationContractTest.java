package com.moakiee.thunderbolt.core.crafting.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;

/**
 * Runtime containment contract for engines reached through the public registry. These hostile
 * probes deliberately hang in engine code so the test covers the same boundary as a registered
 * third-party implementation, rather than only exercising an unrelated executor lambda.
 */
class RegisteredPlanningEngineCancellationContractTest {
    @Test
    void cooperativeRegisteredEngineMayReturnAtTimeoutOnItsVirtualCandidateThread() {
        var ranOnVirtualThread = new AtomicBoolean();
        var timeoutObserved = new AtomicBoolean();
        var interrupted = new AtomicBoolean();
        var engine = register("cooperative_timeout", context -> {
            ranOnVirtualThread.set(Thread.currentThread().isVirtual());
            while (true) {
                try {
                    context.checkpoint();
                } catch (PlanningExitException expected) {
                    timeoutObserved.set(true);
                    interrupted.set(Thread.currentThread().isInterrupted());
                    return PlanningAttempt.DECLINE;
                }
                Thread.onSpinWait();
            }
        });

        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> executeRegistered(engine, 20L, 100L, 1_000L));

        assertEquals(PlanningCandidateExecutor.Status.SUCCESS, result.status());
        assertSame(PlanningAttempt.DECLINE, result.value());
        assertTrue(ranOnVirtualThread.get());
        assertTrue(timeoutObserved.get());
        assertFalse(interrupted.get());
        assertFalse(PlanningCandidateExecutor.isQuarantined(engine.id()));
    }

    @Test
    void ae2StyleInterruptResponsiveEngineIsNotQuarantined() {
        var interrupted = new AtomicBoolean();
        var engine = register("interrupt_responsive", context -> {
            while (!Thread.interrupted()) {
                Thread.onSpinWait();
            }
            interrupted.set(true);
            return PlanningAttempt.DECLINE;
        });

        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> executeRegistered(engine, 20L, 30L, 120L));

        assertEquals(PlanningCandidateExecutor.Status.SUCCESS, result.status());
        assertSame(PlanningAttempt.DECLINE, result.value());
        assertTrue(interrupted.get());
        assertFalse(PlanningCandidateExecutor.isQuarantined(engine.id()));
    }

    @Test
    void nonCooperativeRegisteredEngineIsInterruptedThenDetachedAndQuarantined() throws Exception {
        var started = new CountDownLatch(1);
        var interruptObserved = new CountDownLatch(1);
        var mayReturn = new CountDownLatch(1);
        var stillRunning = new AtomicBoolean();
        var engine = register("non_cooperative_interrupt", context -> {
            stillRunning.set(true);
            started.countDown();
            while (mayReturn.getCount() > 0L) {
                if (Thread.currentThread().isInterrupted()) {
                    interruptObserved.countDown();
                }
                Thread.onSpinWait();
            }
            stillRunning.set(false);
            return PlanningAttempt.DECLINE;
        });

        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> executeRegistered(engine, 20L, 30L, 60L));

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertEquals(PlanningCandidateExecutor.Status.HARD_TIMEOUT, result.status());
        assertTrue(interruptObserved.await(1, TimeUnit.SECONDS));
        try {
            assertTrue(stillRunning.get(), "the caller must detach without waiting for engine return");
            assertTrue(PlanningCandidateExecutor.isQuarantined(engine.id()));
        } finally {
            mayReturn.countDown();
        }

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (PlanningCandidateExecutor.isQuarantined(engine.id())) {
                Thread.onSpinWait();
            }
        });
        assertFalse(stillRunning.get());
    }

    private static ProbeEngine register(String path, Probe probe) {
        var engine = new ProbeEngine(id(path), probe);
        CraftingPlanningEngines.register(engine, 0, false);
        assertSame(engine, CraftingPlanningEngines.get(engine.id()));
        return engine;
    }

    private static PlanningCandidateExecutor.Result<PlanningAttempt> executeRegistered(
            ProbeEngine engine,
            long timeoutMs,
            long interruptGraceMs,
            long isolationGraceMs) throws InterruptedException {
        return PlanningCandidateExecutor.executeForTest(
                engine.id(),
                "registered planning engine cancellation contract",
                context -> {
                    var registered = CraftingPlanningEngines.get(engine.id());
                    try (var session = registered.createSession(null, null, context)) {
                        return session.attempt(1L, false, context);
                    }
                },
                Thread::yield,
                timeoutMs,
                interruptGraceMs,
                isolationGraceMs);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("thunderbolt_contract_test", path);
    }

    @FunctionalInterface
    private interface Probe {
        PlanningAttempt run(PlanningAttemptContext context);
    }

    private record ProbeEngine(ResourceLocation id, Probe probe)
            implements CraftingPlanningEngine {
        @Override
        public boolean check(IGrid grid, PlanningRequest request) {
            return true;
        }

        @Override
        public @Nullable PlanningEngineSession createSession(
                PlanningRequest request,
                @Nullable Object capturedInput,
                PlanningAttemptContext context) {
            return (amount, simulate, attemptContext) -> probe.run(attemptContext);
        }
    }
}
