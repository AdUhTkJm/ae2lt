package com.moakiee.thunderbolt.core.crafting.planner.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPattern;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlan;

class ReferenceCapabilityRunnerTest {
    private static final CraftPattern<String> TARGET_FROM_RAW = new CraftPattern<>(
            "target", 1, List.of(CraftInput.of("raw", 2)), null);
    private static final ReferenceScenario FEASIBLE = new ReferenceScenario(
            "classification/feasible", ReferenceCapability.SINGLE_DAG,
            ReferenceMaterialMode.MINIMUM, 1,
            CraftGraph.<String>builder().stock("target", 1).build(),
            "target", 1, true, List.of(), Map.of());
    private static final ReferenceScenario MISSING = new ReferenceScenario(
            "classification/missing", ReferenceCapability.SINGLE_DAG,
            ReferenceMaterialMode.MISSING, 1,
            CraftGraph.<String>builder().pattern(TARGET_FROM_RAW).build(),
            "target", 1, false, List.of(Map.of("raw", 2L)), Map.of());
    private static final ReferenceCapabilityRunner RUNNER = new ReferenceCapabilityRunner(
            Duration.ofMillis(50), Duration.ofMillis(50));

    @Test
    void onlyProductionPathSuccessCountsAsSupported() {
        var result = RUNNER.run(planner(true, feasiblePlan()), FEASIBLE);

        assertEquals(ReferenceSupportStatus.SUPPORTED, result.status());
        assertTrue(result.supported());
    }

    @Test
    void checkRejectionAndFalseNegativeStayDistinct() {
        var falseNegative = RUNNER.run(planner(false, feasiblePlan()), FEASIBLE);
        var missingFalseNegative = RUNNER.run(
                planner(false, infeasiblePlan(Map.of("raw", 1L))), FEASIBLE);
        var rejected = RUNNER.run(planner(false, declinedPlan()), FEASIBLE);

        assertEquals(ReferenceSupportStatus.FALSE_NEGATIVE, falseNegative.status());
        assertEquals(ReferenceSupportStatus.FALSE_NEGATIVE, missingFalseNegative.status());
        assertEquals(ReferenceSupportStatus.CHECK_REJECTED, rejected.status());
    }

    @Test
    void lateDeclineIsNotAnError() {
        var result = RUNNER.run(planner(true, declinedPlan()), FEASIBLE);

        assertEquals(ReferenceSupportStatus.ATTEMPT_DECLINED, result.status());
    }

    @Test
    void sufficientInventoryReportedMissingIsFalseNegative() {
        var result = RUNNER.run(planner(true, infeasiblePlan(Map.of("raw", 1L))), FEASIBLE);

        assertEquals(ReferenceSupportStatus.FALSE_NEGATIVE, result.status());
    }

    @Test
    void validButNonMinimalMissingReportsOverhead() {
        var result = RUNNER.run(refillAwarePlanner(
                infeasiblePlan(Map.of("raw", 4L)), targetFromRawPlan()), MISSING);

        assertEquals(ReferenceSupportStatus.SUPPORTED, result.status());
        assertEquals(2.0D, result.missingOverhead());
    }

    @Test
    void exactMissingAndSuccessfulRefillIsSupported() {
        var result = RUNNER.run(refillAwarePlanner(
                infeasiblePlan(Map.of("raw", 2L)), targetFromRawPlan()), MISSING);

        assertEquals(ReferenceSupportStatus.SUPPORTED, result.status());
        assertEquals(1.0D, result.missingOverhead());
    }

    @Test
    void refillThatStillReportsMissingIsDeclined() {
        var result = RUNNER.run(refillAwarePlanner(
                infeasiblePlan(Map.of("raw", 1L)),
                infeasiblePlan(Map.of("raw", 1L))), MISSING);

        assertEquals(ReferenceSupportStatus.ATTEMPT_DECLINED, result.status());
    }

    @Test
    void claimedFeasibleButUnexecutableIsFalsePositive() {
        var result = RUNNER.run(planner(true, feasiblePlan()), MISSING);

        assertEquals(ReferenceSupportStatus.FALSE_POSITIVE, result.status());
    }

    @Test
    void uniqueMinimumWithDifferentButExecutableMissingDomainIsPartial() {
        var rawFromAlternative = new CraftPattern<>(
                "raw", 1, List.of(CraftInput.of("alternative", 1)), null);
        var scenario = new ReferenceScenario(
                "classification/alternative-missing", ReferenceCapability.MULTI_DAG,
                ReferenceMaterialMode.MISSING, 1,
                CraftGraph.<String>builder()
                        .pattern(TARGET_FROM_RAW)
                        .pattern(rawFromAlternative)
                        .build(),
                "target", 1, false, List.of(Map.of("raw", 2L)), Map.of());
        var initial = new CraftPlan<>(true, false,
                Map.of(TARGET_FROM_RAW, 1L, rawFromAlternative, 2L),
                Map.of(), Map.of(), Map.of("alternative", 2L), Map.of(), 2, false);
        var refilled = new CraftPlan<>(true, true,
                Map.of(TARGET_FROM_RAW, 1L, rawFromAlternative, 2L),
                Map.of(), Map.of(), Map.of(), Map.of(), 2, false);

        var result = RUNNER.run(refillAwarePlanner(initial, refilled), scenario);

        assertEquals(ReferenceSupportStatus.PARTIALLY_SUPPORTED, result.status());
        assertEquals(1.0D, result.missingOverhead());
    }

    @Test
    void nonUniqueMinimumWithDifferentKindsIsSupportedAtTheSameMinimumCost() {
        var rawFromAlternative = new CraftPattern<>(
                "raw", 1, List.of(CraftInput.of("alternative", 1)), null);
        var graph = CraftGraph.<String>builder()
                .pattern(TARGET_FROM_RAW)
                .pattern(rawFromAlternative)
                .build();
        var scenario = new ReferenceScenario(
                "classification/non-unique", ReferenceCapability.MULTI_DAG,
                ReferenceMaterialMode.MISSING, 1, graph,
                "target", 1, false, List.of(Map.of("raw", 2L)), Map.of(), false,
                graph::withAdditionalStock);
        var initial = new CraftPlan<>(true, false,
                Map.of(TARGET_FROM_RAW, 1L, rawFromAlternative, 2L),
                Map.of(), Map.of(), Map.of("alternative", 2L), Map.of(), 2, false);
        var refilled = new CraftPlan<>(true, true,
                Map.of(TARGET_FROM_RAW, 1L, rawFromAlternative, 2L),
                Map.of(), Map.of(), Map.of(), Map.of(), 2, false);

        var result = RUNNER.run(refillAwarePlanner(initial, refilled), scenario);

        assertEquals(ReferenceSupportStatus.SUPPORTED, result.status());
        assertEquals(1.0D, result.missingOverhead());
    }

    @Test
    void nonUniqueMinimumWithSameKindsAndEnoughTotalIsSupported() {
        var graph = CraftGraph.<String>builder().pattern(TARGET_FROM_RAW).build();
        var scenario = new ReferenceScenario(
                "classification/non-unique-same-kinds", ReferenceCapability.MULTI_DAG,
                ReferenceMaterialMode.MISSING, 1, graph,
                "target", 1, false,
                List.of(Map.of("raw", 2L), Map.of("alternative", 2L)), Map.of(), false,
                graph::withAdditionalStock);

        var result = RUNNER.run(refillAwarePlanner(
                infeasiblePlan(Map.of("raw", 4L)), targetFromRawPlan()), scenario);

        assertEquals(ReferenceSupportStatus.SUPPORTED, result.status());
        assertEquals(2.0D, result.missingOverhead());
    }

    @Test
    void refillClaimingNoMissingButFailingExecutionIsFalsePositive() {
        var result = RUNNER.run(refillAwarePlanner(
                infeasiblePlan(Map.of("raw", 2L)), feasiblePlan()), MISSING);

        assertEquals(ReferenceSupportStatus.FALSE_POSITIVE, result.status());
    }

    @Test
    void exceptionAndCooperativeTimeoutStayDistinct() {
        ReferencePlanner error = new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return true;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) {
                throw new IllegalStateException("boom");
            }
        };
        ReferencePlanner timeout = new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return true;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) throws InterruptedException {
                Thread.sleep(10_000L);
                return feasiblePlan();
            }
        };

        assertEquals(ReferenceSupportStatus.ENGINE_ERROR, RUNNER.run(error, FEASIBLE).status());
        assertEquals(ReferenceSupportStatus.ENGINE_TIMEOUT, RUNNER.run(timeout, FEASIBLE).status());
    }

    @Test
    void nonCooperativeTimeoutIsReportedSeparately() {
        ReferencePlanner planner = new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return true;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) {
                long until = System.nanoTime() + Duration.ofMillis(500).toNanos();
                while (System.nanoTime() < until) {
                    // Deliberately ignore interruption to exercise quarantine classification.
                }
                return feasiblePlan();
            }
        };

        var result = RUNNER.run(planner, FEASIBLE);

        assertEquals(ReferenceSupportStatus.NON_COOPERATIVE_TIMEOUT, result.status());
    }

    @Test
    void checkAndPlanShareOnePassDeadline() {
        var runner = new ReferenceCapabilityRunner(
                Duration.ofMillis(250), Duration.ofMillis(50));
        ReferencePlanner planner = new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) throws InterruptedException {
                Thread.sleep(150L);
                return true;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) throws InterruptedException {
                Thread.sleep(150L);
                return feasiblePlan();
            }
        };

        assertEquals(ReferenceSupportStatus.ENGINE_TIMEOUT, runner.run(planner, FEASIBLE).status());
    }

    @Test
    void refillStartsASecondPassWithAFreshDeadline() {
        var runner = new ReferenceCapabilityRunner(
                Duration.ofMillis(250), Duration.ofMillis(50));
        ReferencePlanner planner = new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return true;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) throws InterruptedException {
                Thread.sleep(150L);
                return scenario.id().endsWith("/refill")
                        ? targetFromRawPlan()
                        : infeasiblePlan(Map.of("raw", 2L));
            }
        };

        assertEquals(ReferenceSupportStatus.SUPPORTED, runner.run(planner, MISSING).status());
    }

    private static ReferencePlanner planner(boolean check, CraftPlan<String> plan) {
        return new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return check;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) {
                return plan;
            }
        };
    }

    private static ReferencePlanner refillAwarePlanner(
            CraftPlan<String> initial, CraftPlan<String> refilled) {
        return new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return true;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) {
                return scenario.id().endsWith("/refill") ? refilled : initial;
            }
        };
    }

    private static CraftPlan<String> feasiblePlan() {
        return new CraftPlan<>(true, true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 1, false);
    }

    private static CraftPlan<String> infeasiblePlan(Map<String, Long> missing) {
        return new CraftPlan<>(true, false, Map.of(TARGET_FROM_RAW, 1L),
                Map.of(), Map.of(), missing, Map.of(), 1, false);
    }

    private static CraftPlan<String> targetFromRawPlan() {
        return new CraftPlan<>(true, true, Map.of(TARGET_FROM_RAW, 1L),
                Map.of("raw", 2L), Map.of(), Map.of(), Map.of(), 1, false);
    }

    private static CraftPlan<String> declinedPlan() {
        return new CraftPlan<>(false, false, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
    }
}
