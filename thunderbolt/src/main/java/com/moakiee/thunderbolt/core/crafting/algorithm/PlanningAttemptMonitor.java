package com.moakiee.thunderbolt.core.crafting.algorithm;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;

/** Generic per-candidate computation budget, staged cancellation and diagnostics. */
final class PlanningAttemptMonitor implements PlanningAttemptContext, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("thunderbolt-crafting-planning");

    private static final long DEFAULT_WARN_MS = Math.max(0L, Long.getLong(
            "thunderbolt.planningWarnMs",
            Long.getLong("thunderbolt.watchdogMs", 2_000L)));
    private static final long DEFAULT_TIMEOUT_MS = Math.max(1L, Long.getLong(
            "thunderbolt.planningTimeoutMs", 3_000L));
    private static final long DEFAULT_INTERRUPT_GRACE_MS = Math.max(0L, Long.getLong(
            "thunderbolt.planningInterruptGraceMs", 2_000L));
    private static final long DEFAULT_STOP_GRACE_MS = Math.max(
            DEFAULT_INTERRUPT_GRACE_MS,
            Long.getLong("thunderbolt.planningStopGraceMs", 5_000L));

    private static final int ACTIVE = 0;
    private static final int EXIT_REQUESTED = 1;
    private static final int INTERRUPT_SENT = 2;
    private static final int ISOLATED = 3;
    private static final int CLOSED = 4;

    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "thunderbolt-planning-monitor");
                thread.setDaemon(true);
                return thread;
            });
    private final ResourceLocation engineId;
    private final String label;
    private final Thread calculationThread;
    private final long startedNanos;
    private final long deadlineNanos;
    private final long interruptDeadlineNanos;
    private final long isolationDeadlineNanos;
    private final long postInterruptGraceMs;
    private final Runnable hardTimeoutAction;
    private final AtomicInteger state = new AtomicInteger(ACTIVE);
    private final ScheduledFuture<?> warningTask;
    private final ScheduledFuture<?> budgetExpiryTask;
    private final ScheduledFuture<?> interruptTask;
    private final ScheduledFuture<?> isolationTask;
    private volatile ScheduledFuture<?> externalCancellationIsolationTask;

    private volatile PlanningDiagnosticSnapshot latest = PlanningDiagnosticSnapshot.phase("starting");
    private volatile boolean externalExitRequested;
    private volatile boolean timeoutObserved;
    private volatile boolean interruptObserved;
    private volatile boolean isolationObserved;

    private PlanningAttemptMonitor(
            ResourceLocation engineId,
            String label,
            long warnMs,
            long timeoutMs,
            long interruptGraceMs,
            long isolationGraceMs,
            Runnable hardTimeoutAction) {
        long boundedInterruptGraceMs = Math.max(0L, interruptGraceMs);
        long boundedIsolationGraceMs = Math.max(
                boundedInterruptGraceMs, isolationGraceMs);
        this.engineId = engineId;
        this.label = label;
        this.calculationThread = Thread.currentThread();
        this.startedNanos = System.nanoTime();
        this.deadlineNanos = saturatedDeadline(startedNanos, timeoutMs);
        this.interruptDeadlineNanos = saturatedDeadline(
                deadlineNanos, boundedInterruptGraceMs);
        this.isolationDeadlineNanos = saturatedDeadline(
                deadlineNanos, boundedIsolationGraceMs);
        this.postInterruptGraceMs = boundedIsolationGraceMs - boundedInterruptGraceMs;
        this.hardTimeoutAction = hardTimeoutAction;
        this.warningTask = warnMs <= 0L || warnMs >= timeoutMs
                ? null
                : EXECUTOR.schedule(this::warnSlow, warnMs, TimeUnit.MILLISECONDS);
        this.budgetExpiryTask = EXECUTOR.schedule(
                this::expireBudget, timeoutMs, TimeUnit.MILLISECONDS);
        this.interruptTask = EXECUTOR.schedule(
                this::interruptAfterGrace,
                saturatedAdd(timeoutMs, boundedInterruptGraceMs), TimeUnit.MILLISECONDS);
        this.isolationTask = EXECUTOR.schedule(
                this::isolateAfterGrace,
                saturatedAdd(timeoutMs, boundedIsolationGraceMs), TimeUnit.MILLISECONDS);
    }

    static PlanningAttemptMonitor start(
            ResourceLocation engineId, String label, Runnable hardTimeoutAction) {
        return new PlanningAttemptMonitor(
                engineId, label, DEFAULT_WARN_MS, DEFAULT_TIMEOUT_MS,
                DEFAULT_INTERRUPT_GRACE_MS, DEFAULT_STOP_GRACE_MS, hardTimeoutAction);
    }

    static PlanningAttemptMonitor startForTest(
            ResourceLocation engineId,
            String label,
            long warnMs,
            long timeoutMs,
            long interruptGraceMs,
            long isolationGraceMs) {
        return new PlanningAttemptMonitor(
                engineId, label, warnMs, timeoutMs,
                interruptGraceMs, isolationGraceMs, () -> { });
    }

    static PlanningAttemptMonitor startForTest(
            ResourceLocation engineId,
            String label,
            long warnMs,
            long timeoutMs,
            long interruptGraceMs,
            long isolationGraceMs,
            Runnable hardTimeoutAction) {
        return new PlanningAttemptMonitor(
                engineId, label, warnMs, timeoutMs,
                interruptGraceMs, isolationGraceMs, hardTimeoutAction);
    }

    @Override
    public long deadlineNanos() {
        return deadlineNanos;
    }

    @Override
    public void checkpoint() {
        int current = state.get();
        long now = System.nanoTime();
        if (current == ISOLATED) {
            isolateCandidate();
            throw new PlanningExitException("planning candidate exceeded its stop grace: " + engineId);
        }
        if (externalExitRequested) {
            publishInterrupt();
            throw new PlanningExitException("planning candidate was cancelled: " + engineId);
        }
        if (now - isolationDeadlineNanos >= 0L) {
            isolateCandidate();
            throw new PlanningExitException("planning candidate exceeded its stop grace: " + engineId);
        }
        if (current == INTERRUPT_SENT || now - interruptDeadlineNanos >= 0L) {
            interruptCandidate();
            throw new PlanningExitException("planning candidate was interrupted after its grace: "
                    + engineId);
        }
        if (current == EXIT_REQUESTED || now - deadlineNanos >= 0L) {
            expireBudget();
            throw new PlanningExitException("planning candidate must exit: " + engineId);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("crafting calculation interrupted");
        }
    }

    /** Atomically accepts a completed result during the grace period and closes its deadlines. */
    void acceptReturnedResult() {
        while (true) {
            int current = state.get();
            long now = System.nanoTime();
            if (current == ISOLATED) {
                isolateCandidate();
                throw new PlanningExitException(
                        "planning candidate exceeded its stop grace: " + engineId);
            }
            if (externalExitRequested) {
                publishInterrupt();
                throw new PlanningExitException("planning candidate was cancelled: " + engineId);
            }
            if (now - isolationDeadlineNanos >= 0L) {
                isolateCandidate();
                throw new PlanningExitException(
                        "planning candidate exceeded its stop grace: " + engineId);
            }
            if (current != INTERRUPT_SENT && now - interruptDeadlineNanos >= 0L) {
                interruptCandidate();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("crafting calculation interrupted");
            }
            if (current == CLOSED || state.compareAndSet(current, CLOSED)) {
                cancelScheduledTasks();
                return;
            }
        }
    }

    @Override
    public void report(PlanningDiagnosticSnapshot snapshot) {
        if (snapshot != null && state.get() == ACTIVE) {
            latest = snapshot;
        }
    }

    boolean timedOut() {
        return timeoutObserved;
    }

    boolean interruptSent() {
        return interruptObserved;
    }

    boolean isolated() {
        return isolationObserved;
    }

    /**
     * Forwards an outer AE2 cancellation as both the cooperative exit state and the native AE2
     * interrupt signal. The caller detaches immediately; quarantine is delayed so an
     * interrupt-responsive candidate can finish its exception unwinding and cleanup normally.
     */
    void requestExitForExternalCancellation() {
        externalExitRequested = true;
        while (true) {
            int current = state.get();
            if (current == CLOSED || current == ISOLATED) {
                return;
            }
            if (current == INTERRUPT_SENT || state.compareAndSet(current, INTERRUPT_SENT)) {
                break;
            }
        }
        cancelNormalDeadlineTasks();
        publishInterrupt();
        scheduleExternalCancellationIsolation();
    }

    private void warnSlow() {
        if (state.get() != ACTIVE) {
            return;
        }
        runDiagnostic(() -> LOG.warn(
                "[Thunderbolt Core] slow planning candidate: engine={} elapsedMs={} {}\n{}",
                engineId, elapsedMillis(), label, diagnosticDump(true)));
    }

    private void expireBudget() {
        if (!state.compareAndSet(ACTIVE, EXIT_REQUESTED)) {
            return;
        }
        timeoutObserved = true;
        runDiagnostic(() -> LOG.warn(
                "[Thunderbolt Core] planning candidate exhausted its computation budget; "
                        + "waiting for cooperative exit: engine={} elapsedMs={} {}\n{}",
                engineId, elapsedMillis(), label, diagnosticDump(true)));
    }

    private void interruptAfterGrace() {
        if (!interruptCandidate()) {
            return;
        }
        runDiagnostic(() -> LOG.warn(
                "[Thunderbolt Core] planning candidate did not exit after cooperative grace; "
                        + "interrupting without quarantine: engine={} elapsedMs={} {}\n{}",
                engineId, elapsedMillis(), label, diagnosticDump(true)));
    }

    private boolean interruptCandidate() {
        while (true) {
            int current = state.get();
            if (current == CLOSED || current == ISOLATED || current == INTERRUPT_SENT) {
                return false;
            }
            if (state.compareAndSet(current, INTERRUPT_SENT)) {
                timeoutObserved = true;
                publishInterrupt();
                return true;
            }
        }
    }

    private void publishInterrupt() {
        calculationThread.interrupt();
        interruptObserved = true;
    }

    private void isolateAfterGrace() {
        if (!isolateCandidate()) {
            return;
        }
        runDiagnostic(() -> LOG.error(
                "[Thunderbolt Core] planning candidate did not exit after interrupt grace; "
                        + "quarantining it now: engine={} elapsedMs={} {}\n{}",
                engineId, elapsedMillis(), label, diagnosticDump(true)));
    }

    private boolean isolateCandidate() {
        interruptCandidate();
        while (true) {
            int current = state.get();
            if (current == CLOSED || current == ISOLATED) {
                return false;
            }
            if (state.compareAndSet(current, ISOLATED)) {
                timeoutObserved = true;
                try {
                    hardTimeoutAction.run();
                } finally {
                    // Publish completion only after quarantine is in effect. The owning
                    // calculation thread can then detach without racing a new invocation.
                    isolationObserved = true;
                }
                return true;
            }
        }
    }

    private String diagnosticDump(boolean includeStack) {
        var snapshot = latest;
        var out = new StringBuilder(256)
                .append("    phase=").append(snapshot.phase());
        for (Map.Entry<String, Long> metric : snapshot.metrics().entrySet()) {
            out.append(' ').append(metric.getKey()).append('=').append(metric.getValue());
        }
        if (includeStack) {
            var stack = calculationThread.getStackTrace();
            out.append("\n    thread '").append(calculationThread.getName()).append("' stack:");
            for (StackTraceElement element : stack) {
                out.append("\n\tat ").append(element);
            }
        }
        return out.toString();
    }

    private long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    @Override
    public void close() {
        state.getAndSet(CLOSED);
        cancelScheduledTasks();
        if (interruptObserved && Thread.currentThread() == calculationThread) {
            Thread.interrupted();
        }
    }

    private void cancelScheduledTasks() {
        if (warningTask != null) {
            warningTask.cancel(false);
        }
        budgetExpiryTask.cancel(false);
        interruptTask.cancel(false);
        isolationTask.cancel(false);
        var externalIsolation = externalCancellationIsolationTask;
        if (externalIsolation != null) {
            externalIsolation.cancel(false);
        }
    }

    private void cancelNormalDeadlineTasks() {
        if (warningTask != null) {
            warningTask.cancel(false);
        }
        budgetExpiryTask.cancel(false);
        interruptTask.cancel(false);
        isolationTask.cancel(false);
    }

    private void scheduleExternalCancellationIsolation() {
        final ScheduledFuture<?> task;
        try {
            task = EXECUTOR.schedule(
                    this::isolateAfterGrace, postInterruptGraceMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingFailure) {
            // Losing the cancellation timer must not leave an abandoned candidate running
            // without a hard boundary.
            isolateCandidate();
            return;
        }
        externalCancellationIsolationTask = task;
        if (state.get() == CLOSED) {
            task.cancel(false);
        }
    }

    private static void runDiagnostic(Runnable diagnostic) {
        Thread.ofVirtual().name("thunderbolt-planning-diagnostics").start(diagnostic);
    }

    private static long saturatedDeadline(long started, long timeoutMs) {
        long nanos;
        try {
            nanos = Math.multiplyExact(timeoutMs, 1_000_000L);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
        long deadline = started + nanos;
        return deadline < started ? Long.MAX_VALUE : deadline;
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
