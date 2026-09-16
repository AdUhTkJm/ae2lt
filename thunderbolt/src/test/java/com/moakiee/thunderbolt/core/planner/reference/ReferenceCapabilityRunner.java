package com.moakiee.thunderbolt.core.crafting.planner.reference;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Runs one author reference case with a hard deadline and classifies the production path. */
public final class ReferenceCapabilityRunner {
    private final Duration deadline;
    private final Duration cancellationObservation;

    /**
     * @param deadline hard limit shared by check + plan in one planning pass; a reported-missing
     *                 refill starts a second planning pass with its own fresh deadline
     * @param cancellationObservation diagnostic-only time used after the hard timeout to distinguish
     *                                a cooperative interruption from a worker that remains alive;
     *                                it never turns a late result into a successful result
     */
    public ReferenceCapabilityRunner(Duration deadline, Duration cancellationObservation) {
        if (deadline.isNegative() || deadline.isZero()
                || cancellationObservation.isNegative() || cancellationObservation.isZero()) {
            throw new IllegalArgumentException(
                    "deadline and cancellation observation must be positive");
        }
        this.deadline = deadline;
        this.cancellationObservation = cancellationObservation;
    }

    public ReferenceRunResult run(ReferencePlanner planner, ReferenceScenario scenario) {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(scenario, "scenario");
        long started = System.nanoTime();

        var checked = invoke(() -> planner.check(scenario), started);
        if (checked.status != InvocationStatus.COMPLETED) {
            return failedInvocation(scenario, checked, started);
        }
        if (!checked.value) {
            // Diagnostic forced execution distinguishes a conservative false negative from a genuine
            // rejection. It never changes production support: check=false remains unsupported.
            var forced = invoke(() -> planner.plan(scenario), started);
            var forcedValidation = forced.status == InvocationStatus.COMPLETED
                    && forced.value != null && forced.value.supported()
                            ? scenario.validate(forced.value)
                            : null;
            if (scenario.expectedFeasible() && forcedValidation != null
                    && (forcedValidation.status() == ReferenceSupportStatus.SUPPORTED
                            || forcedValidation.status() == ReferenceSupportStatus.FALSE_NEGATIVE)) {
                return result(scenario, ReferenceSupportStatus.FALSE_NEGATIVE, started,
                        forcedValidation.missingOverhead(), forced.value, null);
            }
            return result(scenario, ReferenceSupportStatus.CHECK_REJECTED, started,
                    Double.NaN, forced.value, forced.failure);
        }

        var planned = invoke(() -> planner.plan(scenario), started);
        if (planned.status != InvocationStatus.COMPLETED) {
            return failedInvocation(scenario, planned, started);
        }
        if (planned.value == null) {
            return result(scenario, ReferenceSupportStatus.ATTEMPT_DECLINED, started,
                    Double.NaN, null, null);
        }
        if (!planned.value.supported()) {
            return result(scenario, ReferenceSupportStatus.ATTEMPT_DECLINED, started,
                    Double.NaN, planned.value, null);
        }
        var validation = scenario.validate(planned.value);
        if (!scenario.expectedFeasible()
                && (validation.status() == ReferenceSupportStatus.SUPPORTED
                        || validation.status() == ReferenceSupportStatus.PARTIALLY_SUPPORTED
                        || validation.status() == ReferenceSupportStatus.UNKNOWN)) {
            return validateRefill(planner, scenario, planned.value, validation, started);
        }
        return result(scenario, validation.status(), started,
                validation.missingOverhead(), planned.value, null);
    }

    /** A missing report is usable only if supplying it makes a fresh production plan executable. */
    private ReferenceRunResult validateRefill(
            ReferencePlanner planner,
            ReferenceScenario original,
            com.moakiee.thunderbolt.core.crafting.planner.CraftPlan<String> originalPlan,
            ReferenceScenario.Validation initialValidation,
            long started) {
        ReferenceScenario refilled = original.refilled(originalPlan.missing());
        long refillStarted = System.nanoTime();
        var checked = invoke(() -> planner.check(refilled), refillStarted);
        if (checked.status != InvocationStatus.COMPLETED) {
            return failedInvocation(original, checked, started);
        }
        if (!checked.value) {
            return result(original, ReferenceSupportStatus.ATTEMPT_DECLINED, started,
                    Double.NaN, originalPlan, null);
        }

        var planned = invoke(() -> planner.plan(refilled), refillStarted);
        if (planned.status != InvocationStatus.COMPLETED) {
            return failedInvocation(original, planned, started);
        }
        if (planned.value == null || !planned.value.supported()) {
            return result(original, ReferenceSupportStatus.ATTEMPT_DECLINED, started,
                    Double.NaN, originalPlan, null);
        }
        var refillValidation = refilled.validate(planned.value);
        if (refillValidation.status() == ReferenceSupportStatus.FALSE_POSITIVE) {
            return result(original, ReferenceSupportStatus.FALSE_POSITIVE, started,
                    Double.NaN, originalPlan, null);
        }
        if (refillValidation.status() != ReferenceSupportStatus.SUPPORTED) {
            return result(original, ReferenceSupportStatus.ATTEMPT_DECLINED, started,
                    Double.NaN, originalPlan, null);
        }
        return result(original, initialValidation.status(), started,
                initialValidation.missingOverhead(), originalPlan, null);
    }

    private ReferenceRunResult failedInvocation(
            ReferenceScenario scenario, Invocation<?> invocation, long started) {
        var status = switch (invocation.status) {
            case ERROR -> ReferenceSupportStatus.ENGINE_ERROR;
            case TIMEOUT -> ReferenceSupportStatus.ENGINE_TIMEOUT;
            case NON_COOPERATIVE_TIMEOUT -> ReferenceSupportStatus.NON_COOPERATIVE_TIMEOUT;
            case COMPLETED -> throw new IllegalStateException("completed invocation is not a failure");
        };
        return result(scenario, status, started, Double.NaN, null, invocation.failure);
    }

    private ReferenceRunResult result(
            ReferenceScenario scenario,
            ReferenceSupportStatus status,
            long started,
            double missingOverhead,
            com.moakiee.thunderbolt.core.crafting.planner.CraftPlan<String> plan,
            Throwable failure) {
        return new ReferenceRunResult(
                scenario, status, Math.max(0L, System.nanoTime() - started),
                missingOverhead, plan, failure);
    }

    private <T> Invocation<T> invoke(ThrowingSupplier<T> operation, long runStarted) {
        long elapsed = Math.max(0L, System.nanoTime() - runStarted);
        long remaining = deadline.toNanos() - Math.min(deadline.toNanos(), elapsed);
        if (remaining <= 0L) {
            return Invocation.timeout(new java.util.concurrent.TimeoutException(
                    "reference scenario exceeded its shared deadline"));
        }
        var future = new CompletableFuture<T>();
        Thread worker = Thread.ofPlatform()
                .daemon(true)
                .name("thunderbolt-reference-capability")
                .unstarted(() -> {
                    try {
                        future.complete(operation.get());
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                });
        worker.start();
        try {
            return Invocation.completed(future.get(
                    remaining, java.util.concurrent.TimeUnit.NANOSECONDS));
        } catch (java.util.concurrent.TimeoutException timeout) {
            worker.interrupt();
            try {
                long observationNanos = cancellationObservation.toNanos();
                worker.join(observationNanos / 1_000_000L,
                        (int) (observationNanos % 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Invocation.error(interrupted);
            }
            return worker.isAlive()
                    ? Invocation.nonCooperativeTimeout(timeout)
                    : Invocation.timeout(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Invocation.error(interrupted);
        } catch (ExecutionException failed) {
            return Invocation.error(failed.getCause());
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private enum InvocationStatus {
        COMPLETED,
        ERROR,
        TIMEOUT,
        NON_COOPERATIVE_TIMEOUT
    }

    private record Invocation<T>(InvocationStatus status, T value, Throwable failure) {
        static <T> Invocation<T> completed(T value) {
            return new Invocation<>(InvocationStatus.COMPLETED, value, null);
        }

        static <T> Invocation<T> error(Throwable failure) {
            return new Invocation<>(InvocationStatus.ERROR, null, failure);
        }

        static <T> Invocation<T> timeout(Throwable failure) {
            return new Invocation<>(InvocationStatus.TIMEOUT, null, failure);
        }

        static <T> Invocation<T> nonCooperativeTimeout(Throwable failure) {
            return new Invocation<>(InvocationStatus.NON_COOPERATIVE_TIMEOUT, null, failure);
        }
    }
}
