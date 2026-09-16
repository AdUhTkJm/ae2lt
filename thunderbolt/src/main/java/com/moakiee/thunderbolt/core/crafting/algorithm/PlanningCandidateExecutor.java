package com.moakiee.thunderbolt.core.crafting.algorithm;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.core.crafting.planner.PlanningCancellation;

/**
 * Isolates an engine candidate so a non-cooperative implementation cannot block AE2's worker.
 * The monitor first publishes a cooperative deadline, later interrupts without quarantine, then
 * detaches and quarantines only an invocation that still does not return after interrupt grace.
 */
public final class PlanningCandidateExecutor {
    private static final java.util.concurrent.ThreadFactory CANDIDATE_THREADS =
            Thread.ofVirtual().name("thunderbolt-planning-candidate-", 0L).factory();

    /** Number of detached invocations that have not actually returned yet, keyed by engine. */
    private static final ConcurrentHashMap<ResourceLocation, AtomicInteger> QUARANTINED =
            new ConcurrentHashMap<>();

    private PlanningCandidateExecutor() {
    }

    static boolean isQuarantined(ResourceLocation engineId) {
        var count = QUARANTINED.get(engineId);
        return count != null && count.get() > 0;
    }

    /**
     * Runs the isolated candidate's cancellation checkpoint and reports whether AE2's native
     * calculation-thread scheduler must be bypassed on this thread.
     */
    public static boolean checkpointCandidateThread() {
        return PlanningCancellation.checkpointIfBound();
    }

    public static <T> Result<T> execute(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield,
            Consumer<T> discard) throws InterruptedException {
        return execute(engineId, label, work, schedulerYield, discard, PlanningAttemptMonitor::start);
    }

    static <T> Result<T> executeForTest(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield,
            long timeoutMs,
            long interruptGraceMs,
            long isolationGraceMs) throws InterruptedException {
        return executeForTest(
                engineId, label, work, schedulerYield, ignored -> { },
                timeoutMs, interruptGraceMs, isolationGraceMs);
    }

    static <T> Result<T> executeForTest(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield,
            Consumer<T> discard,
            long timeoutMs,
            long interruptGraceMs,
            long isolationGraceMs) throws InterruptedException {
        return execute(
                engineId,
                label,
                work,
                schedulerYield,
                discard,
                (id, taskLabel, hardTimeoutAction) -> PlanningAttemptMonitor.startForTest(
                        id, taskLabel, 0L, timeoutMs,
                        interruptGraceMs, isolationGraceMs, hardTimeoutAction));
    }

    static <T> Result<T> executeWithMonitorForTest(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield,
            MonitorFactory monitorFactory) throws InterruptedException {
        return execute(
                engineId, label, work, schedulerYield, ignored -> { }, monitorFactory);
    }

    private static <T> Result<T> execute(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield,
            Consumer<T> discard,
            MonitorFactory monitorFactory) throws InterruptedException {
        if (isQuarantined(engineId)) {
            return new Result<>(Status.QUARANTINED, null, null);
        }

        var monitorReady = new CompletableFuture<PlanningAttemptMonitor>();
        var result = new CompletableFuture<T>();
        Runnable quarantine = () -> quarantineUntilReturned(engineId, result);
        CANDIDATE_THREADS.newThread(() -> {
            final PlanningAttemptMonitor monitor;
            try {
                monitor = monitorFactory.start(engineId, label, quarantine);
            } catch (Throwable failure) {
                monitorReady.completeExceptionally(failure);
                result.completeExceptionally(failure);
                return;
            }
            monitorReady.complete(monitor);
            try (monitor; var ignored = PlanningCancellation.bind(monitor)) {
                T value = work.run(monitor);
                try {
                    monitor.acceptReturnedResult();
                } catch (Throwable failure) {
                    try {
                        discard.accept(value);
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
                result.complete(value);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }).start();

        PlanningAttemptMonitor monitor;
        try {
            monitor = awaitMonitor(monitorReady, schedulerYield);
        } catch (InterruptedException interrupted) {
            discardWhenReturned(result, discard);
            monitorReady.thenAccept(PlanningAttemptMonitor::requestExitForExternalCancellation);
            throw interrupted;
        }

        try {
            while (true) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("crafting calculation cancelled");
                }
                if (!result.isDone()) {
                    if (monitor.isolated()) {
                        return new Result<>(Status.HARD_TIMEOUT, null, null);
                    }
                    schedulerYield.run();
                    continue;
                }
                try {
                    // isDone is the per-tick gate: get cannot wait once a CompletableFuture is done.
                    T value = result.get();
                    if (Thread.interrupted()) {
                        throw new InterruptedException("crafting calculation cancelled");
                    }
                    return new Result<>(Status.SUCCESS, value, null);
                } catch (ExecutionException failed) {
                    if (Thread.interrupted()) {
                        throw new InterruptedException("crafting calculation cancelled");
                    }
                    Throwable failure = failed.getCause();
                    return new Result<>(
                            monitor.isolated()
                                    ? Status.HARD_TIMEOUT
                                    : monitor.timedOut() ? Status.SOFT_TIMEOUT : Status.FAILED,
                            null,
                            failure);
                }
            }
        } catch (InterruptedException interrupted) {
            discardWhenReturned(result, discard);
            monitor.requestExitForExternalCancellation();
            throw interrupted;
        }
    }

    private static PlanningAttemptMonitor awaitMonitor(
            CompletableFuture<PlanningAttemptMonitor> monitorReady,
            SchedulerYield schedulerYield) throws InterruptedException {
        while (!monitorReady.isDone()) {
            if (Thread.interrupted()) {
                throw new InterruptedException("crafting calculation cancelled");
            }
            schedulerYield.run();
        }
        try {
            return monitorReady.get();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("candidate monitor failed to start", cause);
        }
    }

    private static void quarantineUntilReturned(
            ResourceLocation engineId, CompletableFuture<?> result) {
        var count = QUARANTINED.computeIfAbsent(engineId, ignored -> new AtomicInteger());
        count.incrementAndGet();
        result.whenComplete((ignoredResult, ignoredFailure) -> {
            if (count.decrementAndGet() == 0) {
                QUARANTINED.remove(engineId, count);
            }
        });
    }

    private static <T> void discardWhenReturned(
            CompletableFuture<T> result, Consumer<T> discard) {
        result.thenAccept(discard);
    }

    public enum Status {
        SUCCESS,
        FAILED,
        SOFT_TIMEOUT,
        HARD_TIMEOUT,
        QUARANTINED
    }

    public record Result<T>(Status status, T value, Throwable failure) {
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(PlanningAttemptContext context) throws Throwable;
    }

    @FunctionalInterface
    public interface SchedulerYield {
        void run() throws InterruptedException;
    }

    @FunctionalInterface
    interface MonitorFactory {
        PlanningAttemptMonitor start(
                ResourceLocation engineId, String label, Runnable hardTimeoutAction);
    }
}
