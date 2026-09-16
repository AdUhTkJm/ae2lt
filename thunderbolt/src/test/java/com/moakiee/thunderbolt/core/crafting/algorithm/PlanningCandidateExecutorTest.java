package com.moakiee.thunderbolt.core.crafting.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.PlanningExitException;

class PlanningCandidateExecutorTest {
    @Test
    void monitorStartupFailureCannotLeaveTheCallerYieldingForever() {
        var workRan = new AtomicBoolean();

        var failure = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> PlanningCandidateExecutor.executeWithMonitorForTest(
                                id("monitor_startup_failure"),
                                "monitor startup failure test",
                                context -> {
                                    workRan.set(true);
                                    return 1;
                                },
                                Thread::yield,
                                (engineId, label, hardTimeoutAction) -> {
                                    throw new IllegalStateException("monitor startup failure");
                                })));

        assertEquals("monitor startup failure", failure.getMessage());
        assertFalse(workRan.get());
    }

    @Test
    void cancellationContextIsLimitedToIsolatedWork() throws Exception {
        var candidateMayFinish = new CountDownLatch(1);
        var workBound = new AtomicBoolean();
        var workVirtual = new AtomicBoolean();
        var schedulerBound = new AtomicBoolean(true);

        assertFalse(PlanningCandidateExecutor.checkpointCandidateThread());
        var result = PlanningCandidateExecutor.executeForTest(
                id("candidate_cancellation_context"),
                "candidate cancellation context test",
                context -> {
                    workBound.set(PlanningCandidateExecutor.checkpointCandidateThread());
                    workVirtual.set(Thread.currentThread().isVirtual());
                    assertTrue(candidateMayFinish.await(1, TimeUnit.SECONDS));
                    return 42;
                },
                () -> {
                    schedulerBound.set(PlanningCandidateExecutor.checkpointCandidateThread());
                    candidateMayFinish.countDown();
                    Thread.yield();
                },
                5_000,
                1_000,
                5_000);

        assertEquals(PlanningCandidateExecutor.Status.SUCCESS, result.status());
        assertEquals(42, result.value());
        assertTrue(workBound.get());
        assertTrue(workVirtual.get());
        assertFalse(schedulerBound.get());
        assertFalse(PlanningCandidateExecutor.checkpointCandidateThread());
    }

    @Test
    void candidateSchedulerBypassStillHonorsThePlanningTimeout() {
        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> PlanningCandidateExecutor.executeForTest(
                        id("candidate_scheduler_bypass_timeout"),
                        "candidate scheduler bypass timeout test",
                        context -> {
                            while (true) {
                                assertTrue(PlanningCandidateExecutor.checkpointCandidateThread());
                                Thread.onSpinWait();
                            }
                        },
                        Thread::yield,
                        20,
                        1_000,
                        1_000));

        assertEquals(PlanningCandidateExecutor.Status.SOFT_TIMEOUT, result.status());
        assertTrue(result.failure() instanceof PlanningExitException);
    }

    @Test
    void incompleteCandidateIsCheckedOnlyAfterTheSchedulerResumes() throws Exception {
        var candidateMayFinish = new CountDownLatch(1);
        var candidateReturning = new CountDownLatch(1);
        var firstYield = new CountDownLatch(1);
        var nextTick = new CountDownLatch(1);
        var schedulerYields = new AtomicInteger();
        var outcome = new CompletableFuture<PlanningCandidateExecutor.Result<Integer>>();

        var caller = Thread.ofPlatform().start(() -> {
            try {
                outcome.complete(PlanningCandidateExecutor.executeForTest(
                        id("one_check_per_tick"),
                        "one check per tick test",
                        context -> {
                            assertTrue(candidateMayFinish.await(1, TimeUnit.SECONDS));
                            candidateReturning.countDown();
                            return 42;
                        },
                        () -> {
                            schedulerYields.incrementAndGet();
                            candidateMayFinish.countDown();
                            firstYield.countDown();
                            assertTrue(nextTick.await(1, TimeUnit.SECONDS));
                        },
                        5_000,
                        1_000,
                        5_000));
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            }
        });

        assertTrue(firstYield.await(1, TimeUnit.SECONDS));
        assertTrue(candidateReturning.await(1, TimeUnit.SECONDS));
        Thread.sleep(25L);
        assertEquals(1, schedulerYields.get(),
                "an incomplete result must not be checked again within the same tick");

        nextTick.countDown();
        var result = outcome.get(1, TimeUnit.SECONDS);
        assertEquals(PlanningCandidateExecutor.Status.SUCCESS, result.status());
        assertEquals(42, result.value());
        assertEquals(1, schedulerYields.get());
        caller.join(1_000L);
        assertFalse(caller.isAlive());
    }

    @Test
    void externalCancellationIsExitInsideAndCancellationOutside() throws Exception {
        var engineId = id("external_cancel");
        var started = new CountDownLatch(1);
        var internalExit = new CompletableFuture<Boolean>();
        var outerCancellation = new CompletableFuture<Boolean>();
        var discarded = new CompletableFuture<Integer>();
        var candidateInterrupted = new AtomicBoolean(true);

        var caller = Thread.ofPlatform().start(() -> {
            try {
                PlanningCandidateExecutor.executeForTest(
                        engineId,
                        "external cancellation test",
                        context -> {
                            started.countDown();
                            while (true) {
                                try {
                                    context.checkpoint();
                                } catch (PlanningExitException expected) {
                                    candidateInterrupted.set(Thread.currentThread().isInterrupted());
                                    internalExit.complete(true);
                                    return 42;
                                }
                                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                            }
                        },
                        () -> { },
                        discarded::complete,
                        5_000,
                        1_000,
                        5_000);
                outerCancellation.complete(false);
            } catch (InterruptedException expected) {
                outerCancellation.complete(true);
            } catch (Throwable failure) {
                internalExit.completeExceptionally(failure);
                outerCancellation.completeExceptionally(failure);
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        caller.interrupt();

        assertTrue(internalExit.get(2, TimeUnit.SECONDS));
        assertTrue(outerCancellation.get(2, TimeUnit.SECONDS));
        assertEquals(42, discarded.get(2, TimeUnit.SECONDS));
        assertTrue(candidateInterrupted.get(),
                "AE2 cancellation must publish checkpoint exit and interrupt together");
        caller.join(2_000);
        assertFalse(caller.isAlive());
        assertFalse(
                PlanningCandidateExecutor.isQuarantined(engineId),
                "external cancellation alone must not quarantine the engine");
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (PlanningCandidateExecutor.isQuarantined(engineId)) {
                Thread.onSpinWait();
            }
        });
    }

    @Test
    void interruptResponsiveExternalCancellationExitsWithoutQuarantine() throws Exception {
        var engineId = id("external_cancel_interrupt_responsive");
        var started = new CountDownLatch(1);
        var interruptObserved = new CountDownLatch(1);
        var outerCancellation = new CompletableFuture<Boolean>();
        var discarded = new AtomicInteger();

        var caller = Thread.ofPlatform().start(() -> {
            try {
                PlanningCandidateExecutor.executeForTest(
                        engineId,
                        "interrupt responsive external cancellation test",
                        context -> {
                            started.countDown();
                            while (!Thread.interrupted()) {
                                Thread.onSpinWait();
                            }
                            interruptObserved.countDown();
                            return 1;
                        },
                        Thread::yield,
                        ignored -> discarded.incrementAndGet(),
                        5_000,
                        20,
                        70);
                outerCancellation.complete(false);
            } catch (InterruptedException expected) {
                outerCancellation.complete(true);
            } catch (Throwable failure) {
                outerCancellation.completeExceptionally(failure);
            }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        assertTrue(outerCancellation.get(1, TimeUnit.SECONDS));
        assertTrue(interruptObserved.await(1, TimeUnit.SECONDS));
        caller.join(1_000L);
        assertFalse(caller.isAlive());
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            while (discarded.get() == 0) {
                Thread.onSpinWait();
            }
        });
        assertFalse(PlanningCandidateExecutor.isQuarantined(engineId));
        assertEquals(1, discarded.get());
    }

    @Test
    void nonCooperativeExternalCancellationWaitsAfterInterruptBeforeQuarantine() throws Exception {
        var engineId = id("external_cancel_hard_timeout");
        var started = new CountDownLatch(1);
        var interruptObserved = new CountDownLatch(1);
        var mayReturn = new CountDownLatch(1);
        var outerCancellation = new CompletableFuture<Boolean>();
        var discarded = new AtomicInteger();

        var caller = Thread.ofPlatform().start(() -> {
            try {
                PlanningCandidateExecutor.executeForTest(
                        engineId,
                        "non-cooperative external cancellation test",
                        context -> {
                            started.countDown();
                            while (mayReturn.getCount() > 0L) {
                                if (Thread.currentThread().isInterrupted()) {
                                    interruptObserved.countDown();
                                }
                                Thread.onSpinWait();
                            }
                            return 1;
                        },
                        Thread::yield,
                        ignored -> discarded.incrementAndGet(),
                        5_000,
                        20,
                        220);
                outerCancellation.complete(false);
            } catch (InterruptedException expected) {
                outerCancellation.complete(true);
            } catch (Throwable failure) {
                outerCancellation.completeExceptionally(failure);
            }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        assertTrue(outerCancellation.get(1, TimeUnit.SECONDS));
        caller.join(1_000L);
        assertFalse(caller.isAlive());

        try {
            assertTrue(interruptObserved.await(1, TimeUnit.SECONDS),
                    "external cancellation must forward AE2 interrupt immediately");
            assertFalse(PlanningCandidateExecutor.isQuarantined(engineId),
                    "interrupt delivery must not quarantine at the same boundary");
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                while (!PlanningCandidateExecutor.isQuarantined(engineId)) {
                    Thread.onSpinWait();
                }
            });
        } finally {
            mayReturn.countDown();
        }

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (PlanningCandidateExecutor.isQuarantined(engineId)) {
                Thread.onSpinWait();
            }
        });
        assertEquals(1, discarded.get());
    }

    @Test
    void usableResultReturnedDuringGraceIsAcceptedWithoutInterrupt() {
        var interrupted = new AtomicBoolean();
        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> PlanningCandidateExecutor.executeForTest(
                        id("grace_result"),
                        "grace result test",
                        context -> {
                            Thread.sleep(80L);
                            assertTrue(System.nanoTime() - context.deadlineNanos() >= 0L);
                            org.junit.jupiter.api.Assertions.assertThrows(
                                    PlanningExitException.class, context::checkpoint);
                            interrupted.set(Thread.currentThread().isInterrupted());
                            return 7;
                        },
                        () -> { },
                        20,
                        1_000,
                        1_000));

        assertEquals(PlanningCandidateExecutor.Status.SUCCESS, result.status());
        assertEquals(7, result.value());
        assertFalse(interrupted.get());
    }

    @Test
    void interruptResponsiveCandidateReturnsBeforeIsolationWithoutQuarantine() {
        var engineId = id("responds_to_interrupt");
        var interruptObserved = new AtomicBoolean();

        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> PlanningCandidateExecutor.executeForTest(
                        engineId,
                        "interrupt responsive timeout test",
                        context -> {
                            while (!Thread.interrupted()) {
                                Thread.onSpinWait();
                            }
                            interruptObserved.set(true);
                            return 42;
                        },
                        Thread::yield,
                        20,
                        30,
                        120));

        assertEquals(PlanningCandidateExecutor.Status.SUCCESS, result.status());
        assertEquals(42, result.value());
        assertTrue(interruptObserved.get());
        assertFalse(PlanningCandidateExecutor.isQuarantined(engineId));
    }

    @Test
    void hardTimeoutDetachesNonCooperativeCandidateOnlyAtIsolationDeadline() {
        var stuckId = id("ignores_interrupt");
        var nextId = id("next");
        var stuckStillRunning = new AtomicBoolean();
        var schedulerYields = new AtomicInteger();

        var hardTimedOut = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> PlanningCandidateExecutor.executeForTest(
                        stuckId,
                        "non-cooperative test",
                        context -> {
                            stuckStillRunning.set(true);
                            long finishAt = System.nanoTime() + Duration.ofMillis(250).toNanos();
                            while (System.nanoTime() - finishAt < 0L) {
                                Thread.onSpinWait();
                            }
                            stuckStillRunning.set(false);
                            return 1;
                        },
                        schedulerYields::incrementAndGet,
                        20,
                        30,
                        60));

        assertEquals(PlanningCandidateExecutor.Status.HARD_TIMEOUT, hardTimedOut.status());
        assertTrue(stuckStillRunning.get(), "the ignored interrupt must not hold up the caller");
        assertTrue(PlanningCandidateExecutor.isQuarantined(stuckId));
        assertTrue(schedulerYields.get() > 0);

        var next = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> PlanningCandidateExecutor.executeForTest(
                        nextId, "next test", context -> 42, () -> { }, 100, 100, 200));
        assertEquals(PlanningCandidateExecutor.Status.SUCCESS, next.status());
        assertEquals(42, next.value());

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (PlanningCandidateExecutor.isQuarantined(stuckId)) {
                Thread.onSpinWait();
            }
        });
        assertFalse(stuckStillRunning.get());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("thunderbolt_test", path);
    }
}
