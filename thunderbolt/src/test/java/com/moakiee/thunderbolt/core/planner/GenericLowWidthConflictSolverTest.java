package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class GenericLowWidthConflictSolverTest {

    @Test
    void sharedResourceSelectsConcreteMissingRoute() {
        CraftPattern<String> viaA = new CraftPattern<>(
                "D", 1, List.of(CraftInput.of("A", 1), CraftInput.of("C", 2)), "viaA");
        CraftPattern<String> viaB = new CraftPattern<>(
                "D", 1, List.of(CraftInput.of("B", 1), CraftInput.of("C", 1)), "viaB");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(viaA)
                .pattern(viaB)
                .build();

        PlanningResult<String> result = CraftPlannerV2.planDetailed(graph, "D", 1);

        assertFalse(result.plan().feasible());
        assertEquals(Map.of("B", 1L, "C", 1L), result.plan().missing());
        assertEquals(0, result.diagnostics().lowWidthAttempts(),
                "the optimistic capacity proof should reject this before building a matrix");
        assertEquals(1, result.diagnostics().lowWidthInfeasible());
        assertEquals(1, result.diagnostics().planRuns());
        assertEquals(0, result.diagnostics().consumedFallbackBudget());
    }

    @Test
    void aFeasiblePrivateRouteStillBeatsLowerSharedConsumption() {
        CraftPattern<String> viaA = new CraftPattern<>(
                "D", 1, List.of(CraftInput.of("A", 1), CraftInput.of("C", 2)), "viaA");
        CraftPattern<String> viaB = new CraftPattern<>(
                "D", 1, List.of(CraftInput.of("B", 1), CraftInput.of("C", 1)), "viaB");
        CraftPlan<String> plan = CraftPlannerV2.plan(
                CraftGraph.<String>builder()
                        .pattern(viaA)
                        .pattern(viaB)
                        .stock("A", 1)
                        .stock("C", 2)
                        .build(),
                "D",
                1);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.firings().getOrDefault(viaA, 0L));
        assertEquals(0L, plan.firings().getOrDefault(viaB, 0L));
    }

    @Test
    void orderedValidationRejectsRelaxedHostMatchAndResolvesWithNoGood() {
        var left = new ReusableStockSource("host", "left-pool", "left-route");
        var right = new ReusableStockSource("host", "right-pool", "right-route");
        CraftPattern<String> good = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("special", 1)), "good");
        CraftPattern<String> relaxedBad = new CraftPattern<>(
                "X",
                1,
                List.of(
                        CraftInput.returnedFrom("left-seed", 1, left),
                        CraftInput.returnedFrom("right-seed", 1, right)),
                "relaxed-bad");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                // Coordinate minimization removes the first route when both look feasible, making
                // the relaxed fuzzy-host candidate the first vector sent to ordered validation.
                .pattern(good)
                .pattern(relaxedBad)
                .stock("special", 1)
                .reusableStock("host", "shared", 1)
                .reusableStock("host", "unrelated", 1)
                .reusableStockRoute(left, "left-seed", List.of("shared"))
                .reusableStockRoute(right, "right-seed", List.of("shared"))
                .build();

        PlanningResult<String> result = CraftPlannerV2.planDetailed(graph, "X", 1);

        assertTrue(result.plan().feasible());
        assertEquals(1L, result.plan().usedStock().get("special"));
        assertTrue(result.plan().usedReusableStock().isEmpty(),
                "the rejected validator attempt must be completely rolled back");
        assertEquals(0L, result.plan().firings().getOrDefault(relaxedBad, 0L));
        assertEquals(1L, result.plan().firings().getOrDefault(good, 0L));
        assertEquals(2, result.diagnostics().lowWidthAttempts(),
                "one initial solve plus one support no-good re-solve");
        assertEquals(2, result.diagnostics().lowWidthSolved());
        assertEquals(0, result.diagnostics().consumedSearchBudget());
        assertEquals(1, result.diagnostics().planRuns());
    }

    @Test
    void starvedRenamedTwoRouteRecurrenceUsesSameWidthThreeProof() {
        long amount = 1_000_000_000L;
        PlanningResult<String> first = assertStarvedRecurrence("left-", 30, amount);
        PlanningResult<String> renamed = assertStarvedRecurrence("renamed-", 30, amount);

        assertEquals(baseMissing(first.plan(), "left-"), baseMissing(renamed.plan(), "renamed-"));
        assertEquals(first.diagnostics().separatorWidthPeak(), renamed.diagnostics().separatorWidthPeak());
        assertEquals(first.plan().itemsProcessed(), renamed.plan().itemsProcessed());
    }

    @Test
    void starvedDepthThirtyTwoRecurrenceReportsMinimumRawCostWithinOneSecond() {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (int i = 3; i <= 32; i++) {
            builder.pattern("X" + i, 1, List.of(
                    CraftInput.of("X" + (i - 1), 1),
                    CraftInput.of("X" + (i - 2), 1)));
            builder.pattern("X" + i, 1, List.of(
                    CraftInput.of("X" + (i - 2), 1),
                    CraftInput.of("X" + (i - 3), 1)));
        }

        PlanningResult<String> result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> CraftPlannerV2.planDetailed(builder.build(), "X32", 1));

        assertFalse(result.plan().feasible());
        assertEquals(Map.of("X1", 2_513L, "X2", 3_329L), result.plan().missing());
        assertEquals(5_842L, result.plan().missing().values().stream()
                .mapToLong(Long::longValue)
                .sum());
        assertFalse(result.plan().budgetExhausted());
        assertEquals(1, result.diagnostics().lowWidthInfeasible());
        assertEquals(0, result.diagnostics().lowWidthAttempts());
        assertFalse(result.diagnostics().searchCutoff());
        assertFalse(result.diagnostics().resolutionCutoff());
        assertFalse(result.diagnostics().fallbackCutoff());
    }

    @Test
    void longRequestWithPartialBaseInventoryStaysOnLinearPath() {
        int depth = 30;
        long amount = 1_000_000_000L;
        CraftGraph.Builder<String> builder = twoRouteRecurrence("partial-", depth);
        // The stable first route only reaches bases 1 and 2. This is deliberately partial by item
        // type, while the quantities are large enough to prove work does not scale with Q.
        builder.stock("partial-1", Sat.SAT).stock("partial-2", Sat.SAT);

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                builder.build(), "partial-" + (depth - 1), amount);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(0, result.diagnostics().lowWidthAttempts());
        assertEquals(0, result.diagnostics().consumedFallbackBudget());
        assertEquals(1, result.diagnostics().planRuns());
    }

    @Test
    void longMultiLevelRecurrenceUsesOneGenericIntegerFlowSolve() {
        long amount = 1_000_000_000L;

        PlanningResult<String> result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2), () -> CraftPlannerV2.planDetailed(
                        twoRouteRecurrence("mixed-", 7)
                                .stock("mixed-1", 2 * amount)
                                .stock("mixed-2", 2 * amount)
                                .build(),
                        "mixed-6",
                        amount));

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(3, result.plan().firings().size());
        assertTrue(result.plan().firings().values().stream().allMatch(value -> value == amount));
        assertEquals(3, result.diagnostics().separatorWidthPeak());
        assertEquals(1, result.diagnostics().lowWidthSolved());
        assertTrue(result.diagnostics().lowWidthIntegerNodes() <= 8);
        assertEquals(0, result.diagnostics().dynamicCapacityEvaluations());
        assertEquals(0, result.diagnostics().consumedFallbackBudget());
    }

    @Test
    void coefficientVariantsUseTheSameWidthBoundAndCapacityProof() {
        int[][] variants = {
                {1, 2, 1, 1},
                {2, 2, 1, 1},
                {1, 1, 1, 2},
                {1, 2, 2, 1}
        };
        long amount = 1_000_000_000L;

        for (int variant = 0; variant < variants.length; variant++) {
            int[] coefficients = variants[variant];
            String prefix = "weighted-" + variant + "-";
            PlanningResult<String> result = CraftPlannerV2.planDetailed(
                    twoRouteRecurrence(prefix, 30, coefficients).build(),
                    prefix + "29",
                    amount);

            assertFalse(result.plan().feasible());
            assertTrue(result.plan().missing().keySet().stream()
                    .allMatch(key -> key.equals(prefix + "0")
                            || key.equals(prefix + "1")
                            || key.equals(prefix + "2")));
            assertEquals(3, result.diagnostics().separatorWidthPeak());
            assertEquals(1, result.diagnostics().lowWidthInfeasible());
            assertEquals(0, result.diagnostics().lowWidthAttempts());
            assertEquals(0, result.diagnostics().consumedFallbackBudget());
        }
    }

    @Test
    void genericIntegerFlowRejectsDoubleCountedSharedCapacity() {
        CraftPattern<String> viaShared = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 1), CraftInput.of("D", 1)), "viaShared");
        CraftPattern<String> viaIron = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("iron", 1)), "viaIron");
        CraftPattern<String> targetViaA = new CraftPattern<>(
                "target", 1, List.of(CraftInput.of("A", 1)), "targetViaA");
        CraftPattern<String> targetViaDeadEnd = new CraftPattern<>(
                "target", 1, List.of(CraftInput.of("dead", 1)), "targetViaDeadEnd");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(targetViaA)
                .pattern(targetViaDeadEnd)
                .pattern(viaShared)
                .pattern(viaIron)
                .pattern("B", 1, List.of(CraftInput.of("shared", 1)))
                .pattern("D", 1, List.of(CraftInput.of("shared", 1)))
                .stock("shared", 4)
                .stock("iron", 3)
                .build();

        PlanningResult<String> result = CraftPlannerV2.planDetailed(graph, "target", 3);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(0L, result.plan().firings().getOrDefault(viaShared, 0L));
        assertEquals(3L, result.plan().firings().getOrDefault(viaIron, 0L));
        assertEquals(3L, result.plan().firings().getOrDefault(targetViaA, 0L));
        assertEquals(0L, result.plan().firings().getOrDefault(targetViaDeadEnd, 0L));
        assertEquals(1, result.diagnostics().lowWidthSolved());
        assertEquals(0, result.diagnostics().dynamicCapacityEvaluations());
        assertTrue(result.diagnostics().lowWidthIntegerNodes() <= 8);
    }

    @Test
    void ordinaryDagDoesNotCreateLowWidthIntegerWork() {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        int depth = 2_000;
        for (int i = 0; i < depth; i++) {
            builder.pattern("n" + i, 1, List.of(CraftInput.of("n" + (i + 1), 1)));
        }
        builder.stock("n" + depth, 1);

        PlanningResult<String> result = CraftPlannerV2.planDetailed(builder.build(), "n0", 1);

        assertTrue(result.plan().feasible());
        assertEquals(0, result.diagnostics().lowWidthAttempts());
        assertEquals(0, result.diagnostics().lowWidthIntegerNodes());
    }

    @Test
    void globallyWideFrontierStillSolvesEveryIndependentNarrowComponent() {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        List<CraftInput<String>> roots = new ArrayList<>();
        int componentCount = 11;
        for (int component = 0; component < componentCount; component++) {
            String prefix = "independent-" + component + "-";
            appendTwoRouteRecurrence(builder, prefix, null, 7, new int[] {1, 1, 1, 1});
            builder.stock(prefix + "1", 2).stock(prefix + "2", 2);
            roots.add(CraftInput.of(prefix + "6", 1));
        }
        // A monolithic separator reaches 13 here: eleven roots plus the active recurrence frontier.
        // Component-local measurement must remain three and solve every kernel independently.
        builder.pattern("independent-root", 1, roots);

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                builder.build(), "independent-root", 1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(3, result.diagnostics().separatorWidthPeak());
        assertEquals(componentCount, result.diagnostics().lowWidthAttempts());
        assertEquals(componentCount, result.diagnostics().lowWidthSolved());
        assertEquals(0, result.diagnostics().dynamicCapacityEvaluations());
        assertEquals(0, result.diagnostics().consumedFallbackBudget());
    }

    @Test
    void widthSixteenComponentDoesNotVetoTwoWidthEightSiblings() {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        List<CraftInput<String>> wideInputs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String raw = "wide-raw-" + i;
            wideInputs.add(CraftInput.of(raw, 1));
            builder.stock(raw, 1);
        }
        CraftPattern<String> wideFeasible = new CraftPattern<>(
                "wide-top", 1, wideInputs, "wide-feasible");
        CraftPattern<String> wideDead = new CraftPattern<>(
                "wide-top", 1, List.of(CraftInput.of("wide-dead", 1)), "wide-dead");
        builder.pattern(wideFeasible).pattern(wideDead);

        appendWidthEightRecurrence(builder, "narrow-a-");
        appendWidthEightRecurrence(builder, "narrow-b-");
        builder.pattern("mixed-root", 1, List.of(
                CraftInput.of("wide-top", 1),
                CraftInput.of("narrow-a-6", 1),
                CraftInput.of("narrow-b-6", 1)));

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                builder.build(), "mixed-root", 1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(16, result.diagnostics().separatorWidthPeak());
        assertEquals(2, result.diagnostics().lowWidthAttempts());
        assertEquals(2, result.diagnostics().lowWidthSolved());
        assertEquals(1L, result.plan().firings().getOrDefault(wideFeasible, 0L));
        assertEquals(0L, result.plan().firings().getOrDefault(wideDead, 0L));
        assertEquals(0, result.diagnostics().consumedFallbackBudget());
    }

    @Test
    void sharedInventoryRowsMergeDecisionConesBeforeSolving() {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        appendTwoRouteRecurrence(
                builder, "merged-a-", "merged-base-", 7, new int[] {1, 1, 1, 1});
        appendTwoRouteRecurrence(
                builder, "merged-b-", "merged-base-", 7, new int[] {1, 1, 1, 1});
        builder.stock("merged-base-1", 4).stock("merged-base-2", 4);
        builder.pattern("merged-root", 1, List.of(
                CraftInput.of("merged-a-6", 1), CraftInput.of("merged-b-6", 1)));

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                builder.build(), "merged-root", 1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(1, result.diagnostics().lowWidthAttempts(),
                "shared base inventory is one coupled component, not two independent solves");
        assertEquals(1, result.diagnostics().lowWidthSolved());
        assertTrue(result.diagnostics().separatorWidthPeak() <= 12);
    }

    @Test
    void reusableByproductFeedsContendedSiblingAndSurplusIsDiscarded() {
        CraftPattern<String> producesToken = new CraftPattern<>(
                "P",
                1,
                List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("token", 10), CraftOutput.of("unused-waste", 1_000)),
                "produces-token");
        CraftPattern<String> deadP = new CraftPattern<>(
                "P", 1, List.of(CraftInput.of("dead-p", 1)), "dead-p");
        CraftPattern<String> consumesToken = new CraftPattern<>(
                "Q", 1, List.of(CraftInput.of("token", 1)), "consumes-token");
        CraftPattern<String> deadQ = new CraftPattern<>(
                "Q", 1, List.of(CraftInput.of("dead-q", 1)), "dead-q");
        CraftPattern<String> root = new CraftPattern<>(
                "root", 1, List.of(CraftInput.of("P", 1), CraftInput.of("Q", 1)), "root");

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                CraftGraph.<String>builder()
                        .pattern(root)
                        .pattern(producesToken)
                        .pattern(deadP)
                        .pattern(consumesToken)
                        .pattern(deadQ)
                        .stock("raw", 1)
                        .build(),
                "root",
                1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(1L, result.plan().firings().getOrDefault(producesToken, 0L));
        assertEquals(1L, result.plan().firings().getOrDefault(consumesToken, 0L));
        assertEquals(0L, result.plan().usedStock().getOrDefault("token", 0L));
        assertEquals(0L, result.plan().usedStock().getOrDefault("unused-waste", 0L));
        assertEquals(0, result.diagnostics().lowWidthSolved(),
                "an acyclic producer-before-consumer order should finish in the linear backbone");
    }

    @Test
    void unrelatedByproductDoesNotDisableIndependentNarrowComponents() {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        appendTwoRouteRecurrence(
                builder, "bp-independent-a-", null, 7, new int[] {1, 1, 1, 1});
        appendTwoRouteRecurrence(
                builder, "bp-independent-b-", null, 7, new int[] {1, 1, 1, 1});
        builder.stock("bp-independent-a-1", 2)
                .stock("bp-independent-a-2", 2)
                .stock("bp-independent-b-1", 2)
                .stock("bp-independent-b-2", 2)
                .pattern(
                        "bp-independent-root",
                        1,
                        List.of(
                                CraftInput.of("bp-independent-a-6", 1),
                                CraftInput.of("bp-independent-b-6", 1)),
                        List.of(CraftOutput.of("irrelevant-waste", 1_000_000)));

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                builder.build(), "bp-independent-root", 1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(2, result.diagnostics().lowWidthAttempts());
        assertEquals(2, result.diagnostics().lowWidthSolved());
    }

    @Test
    void byproductCreditNeverJustifiesExtraPrimaryFirings() {
        CraftPattern<String> producesTenTokens = new CraftPattern<>(
                "P",
                1,
                List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("token", 10)),
                "produces-ten-tokens");
        CraftPattern<String> deadP = new CraftPattern<>(
                "P", 1, List.of(CraftInput.of("dead-p", 1)), "dead-p");
        CraftPattern<String> needsElevenTokens = new CraftPattern<>(
                "Q", 1, List.of(CraftInput.of("token", 11)), "needs-eleven-tokens");
        CraftPattern<String> deadQ = new CraftPattern<>(
                "Q", 1, List.of(CraftInput.of("dead-q", 1)), "dead-q");

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                CraftGraph.<String>builder()
                        .pattern("root", 1, List.of(
                                CraftInput.of("P", 1), CraftInput.of("Q", 1)))
                        .pattern(producesTenTokens)
                        .pattern(deadP)
                        .pattern(needsElevenTokens)
                        .pattern(deadQ)
                        .stock("raw", 2)
                        .build(),
                "root",
                1);

        assertFalse(result.plan().feasible());
        assertTrue(result.plan().firings().getOrDefault(producesTenTokens, 0L) <= 1L,
                "the second P firing would exist only to manufacture a byproduct");
        assertEquals(1L, result.plan().missing().getOrDefault("token", 0L));
    }

    @Test
    void batchReplayDoesNotCaptureIncrementalUnitOutputInTheSameComponent() {
        CraftPattern<String> batch = new CraftPattern<>(
                "B",
                2,
                List.of(CraftInput.of("shared", 1), CraftInput.of("batch-ticket", 1)),
                List.of(CraftOutput.of("batch-token", 1)),
                "batch");
        CraftPattern<String> deadBatch = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("dead-batch", 1)), "dead-batch");
        CraftPattern<String> unitLeft = new CraftPattern<>(
                "U",
                1,
                List.of(CraftInput.of("shared", 1), CraftInput.of("left-ticket", 1)),
                List.of(CraftOutput.of("left-token", 1)),
                "unit-left");
        CraftPattern<String> unitRight = new CraftPattern<>(
                "U",
                1,
                List.of(CraftInput.of("shared", 1), CraftInput.of("right-ticket", 1)),
                List.of(CraftOutput.of("right-token", 1)),
                "unit-right");

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                CraftGraph.<String>builder()
                        .pattern("root", 1, List.of(
                                CraftInput.of("U", 1),
                                CraftInput.of("U", 1),
                                CraftInput.of("B", 1),
                                CraftInput.of("left-token", 1),
                                CraftInput.of("right-token", 1),
                                CraftInput.of("batch-token", 1)))
                        .pattern(batch)
                        .pattern(deadBatch)
                        .pattern(unitLeft)
                        .pattern(unitRight)
                        .stock("shared", 3)
                        .stock("batch-ticket", 1)
                        .stock("left-ticket", 1)
                        .stock("right-ticket", 1)
                        .build(),
                "root",
                1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing()
                + " diagnostics=" + result.diagnostics());
        assertEquals(1L, result.plan().firings().getOrDefault(batch, 0L));
        assertEquals(1L, result.plan().firings().getOrDefault(unitLeft, 0L));
        assertEquals(1L, result.plan().firings().getOrDefault(unitRight, 0L));
        assertEquals(1, result.diagnostics().lowWidthSolved());
    }

    @Test
    void returnedCatalystUsesOneActivationReserveInsideGenericFlow() {
        long amount = 1_000_000_000L;
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (int level = 3; level < 7; level++) {
            builder.pattern(new CraftPattern<>(
                    "catalyst-" + level,
                    1,
                    List.of(
                            CraftInput.of("catalyst-" + (level - 1), 1),
                            CraftInput.of("catalyst-" + (level - 2), 1),
                            CraftInput.returned("tool", 1)),
                    "left-" + level));
            builder.pattern(new CraftPattern<>(
                    "catalyst-" + level,
                    1,
                    List.of(
                            CraftInput.of("catalyst-" + (level - 2), 1),
                            CraftInput.of("catalyst-" + (level - 3), 1),
                            CraftInput.returned("tool", 1)),
                    "right-" + level));
        }
        builder.stock("catalyst-1", 2 * amount)
                .stock("catalyst-2", 2 * amount)
                .stock("tool", 1);

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                builder.build(), "catalyst-6", amount);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(1L, result.plan().usedStock().getOrDefault("tool", 0L));
        assertEquals(1, result.diagnostics().lowWidthSolved());
        assertTrue(result.diagnostics().lowWidthIntegerNodes() <= 64);
    }

    @Test
    void nearIntegralStatefulChainFallsBackBeforeExactRationalWorkCanStall() {
        PlanningResult<String> result =
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () -> CraftPlannerV2.planDetailed(
                                nearIntegralStatefulChain(24), "budget-root", 1));

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertFalse(result.plan().budgetExhausted(),
                "optional exact cutoff must not poison the ordinary planner");
        assertTrue(result.diagnostics().lowWidthCutoffs() >= 1,
                "the dense stateful component must be declined generically");
        assertTrue(result.diagnostics().lowWidthIntegerNodes() <= 4,
                "the cutoff must happen before repeated rational relaxations");
    }

    @Test
    void craftLessProbesShareCompilationAndExactWorkBudget() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2), () -> {
                    CraftGraph<String> graph = nearIntegralStatefulChain(12, 1_023L);
                    var session = new CraftPlannerV2.PlanningSession<String>();
                    PlanningResult<String> requested = CraftPlannerV2.planDetailed(
                            graph, "budget-root", 1_024L, session);
                    assertFalse(requested.plan().feasible());

                    long best = 0L;
                    boolean sawPreparedReuse = false;
                    boolean sawSpentExactBudget = false;
                    for (long bit = Long.highestOneBit(1_024L); bit > 0L; bit >>>= 1) {
                        long candidate = best + bit;
                        if (candidate >= 1_024L) continue;
                        PlanningResult<String> probe = CraftPlannerV2.planDetailed(
                                graph, "budget-root", candidate, session);
                        sawPreparedReuse |= probe.diagnostics().reusedCompilations() > 0;
                        sawSpentExactBudget |= probe.diagnostics().lowWidthCutoffs() > 0
                                && probe.diagnostics().lowWidthIntegerNodes() == 0;
                        if (probe.plan().feasible()) {
                            best = candidate;
                        }
                    }

                    assertEquals(1_023L, best);
                    assertTrue(sawPreparedReuse,
                            "quantity probes must reuse the normalized graph orientation");
                    assertTrue(sawSpentExactBudget,
                            "later probes must not restart a fresh rational-solver budget");
                });
    }

    @Test
    void deliberatelyHugeReachableGraphReturnsConservativeMissingBeforeCompilation() {
        int fanout = 14_000;
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        List<CraftOutput<String>> byproducts = new ArrayList<>(fanout);
        List<CraftInput<String>> rootInputs = new ArrayList<>(fanout + 1);
        rootInputs.add(CraftInput.of("huge-producer", 1));
        for (int i = 0; i < fanout; i++) {
            String token = "huge-token-" + i;
            String consumer = "huge-consumer-" + i;
            byproducts.add(CraftOutput.of(token, 1));
            builder.pattern(consumer, 1, List.of(CraftInput.of(token, 1)));
            rootInputs.add(CraftInput.of(consumer, 1));
        }
        builder.pattern(new CraftPattern<>(
                "huge-producer", 1, List.of(CraftInput.of("huge-raw", 1)),
                byproducts, "huge-fanout"));
        builder.pattern("huge-root", 1, rootInputs).stock("huge-raw", 1);
        CraftGraph<String> graph = builder.build();

        PlanningResult<String> result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> CraftPlannerV2.planDetailed(graph, "huge-root", 1));

        assertFalse(result.plan().feasible());
        assertTrue(result.plan().budgetExhausted());
        assertEquals(Map.of("huge-root", 1L), result.plan().missing());
        assertEquals(0, result.diagnostics().planRuns());
        assertEquals(0, result.diagnostics().compiledOrientations());
    }

    @Test
    void interruptedPlanningThreadPropagatesCancellationWithoutClearingInterrupt() throws Exception {
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("cancel-target", 1, List.of(CraftInput.of("cancel-raw", 1)))
                .stock("cancel-raw", 1)
                .build();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                Thread.currentThread().interrupt();
                assertThrows(CancellationException.class,
                        () -> CraftPlannerV2.plan(graph, "cancel-target", 1));
                return Thread.currentThread().isInterrupted();
            });
            assertTrue(future.get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void independentCalculationSessionsRunConcurrentlyWithoutBudgetCrossTalk() throws Exception {
        CraftGraph<String> graph = nearIntegralStatefulChain(12, 1L);
        int jobs = 4;
        var executor = Executors.newFixedThreadPool(jobs);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<PlanningResult<String>>>(jobs);
            for (int i = 0; i < jobs; i++) {
                futures.add(executor.submit(
                        () -> CraftPlannerV2.planDetailed(graph, "budget-root", 1)));
            }
            for (var future : futures) {
                PlanningResult<String> result = future.get(5, TimeUnit.SECONDS);
                assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
                assertEquals(1, result.diagnostics().compiledOrientations());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void selfGainMacroStartsFromOneAcyclicSeedProducer() {
        CraftPattern<String> gain = new CraftPattern<>(
                "X", 1, List.of(CraftInput.returned("X", 1)), "contracted-gain");
        CraftPattern<String> seed = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("seed-raw", 1)), "seed-producer");
        CraftPattern<String> yGood = new CraftPattern<>(
                "Y", 1, List.of(CraftInput.of("y-raw", 1)), "y-good");
        CraftPattern<String> yDead = new CraftPattern<>(
                "Y", 1, List.of(CraftInput.of("y-dead", 1)), "y-dead");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("root", 1, List.of(CraftInput.of("X", 100), CraftInput.of("Y", 1)))
                .pattern(gain)
                .pattern(seed)
                .pattern(yGood)
                .pattern(yDead)
                .stock("seed-raw", 1)
                .stock("y-raw", 1)
                .build();

        PlanningResult<String> result = CraftPlannerV2.planDetailed(graph, "root", 1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(100L, result.plan().firings().getOrDefault(gain, 0L));
        assertEquals(1L, result.plan().firings().getOrDefault(seed, 0L));
        assertEquals(1L, result.plan().usedStock().getOrDefault("seed-raw", 0L));
        assertTrue(result.diagnostics().seedOrdered());
        assertTrue(result.diagnostics().lowWidthSolved() >= 1);
    }

    @Test
    void unseededSelfGainCannotWinAnAlgebraicRouteChoice() {
        CraftPattern<String> rootViaLoop = new CraftPattern<>(
                "root", 1, List.of(CraftInput.of("X", 1)), "root-via-loop");
        CraftPattern<String> rootViaSafe = new CraftPattern<>(
                "root", 1,
                List.of(CraftInput.of("safe", 1), CraftInput.of("Q", 1)),
                "root-via-safe");
        CraftPattern<String> gain = new CraftPattern<>(
                "X", 1, List.of(CraftInput.returned("X", 1)), "unseeded-gain");
        CraftPattern<String> qGood = new CraftPattern<>(
                "Q", 1, List.of(CraftInput.of("q-raw", 1)), "q-good");
        CraftPattern<String> qDead = new CraftPattern<>(
                "Q", 1, List.of(CraftInput.of("q-dead", 1)), "q-dead");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(rootViaLoop)
                .pattern(rootViaSafe)
                .pattern(gain)
                .pattern(qGood)
                .pattern(qDead)
                .stock("safe", 1)
                .stock("q-raw", 1)
                .build();

        PlanningResult<String> result = CraftPlannerV2.planDetailed(graph, "root", 1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(0L, result.plan().firings().getOrDefault(rootViaLoop, 0L));
        assertEquals(0L, result.plan().firings().getOrDefault(gain, 0L));
        assertEquals(1L, result.plan().firings().getOrDefault(rootViaSafe, 0L));
        assertTrue(result.diagnostics().lowWidthSolved() >= 1);
    }

    @Test
    void dedicatedLoopPoolsShareThePhysicalHostCapacityConstraint() {
        var leftSource = new ReusableStockSource("host", "left-loop");
        var rightSource = new ReusableStockSource("host", "right-loop");
        CraftPattern<String> leftLoop = new CraftPattern<>(
                "left", 1, List.of(CraftInput.returnedFrom("seed", 1, leftSource)), "left-loop");
        CraftPattern<String> leftRaw = new CraftPattern<>(
                "left", 1, List.of(CraftInput.of("left-raw", 1)), "left-raw");
        CraftPattern<String> rightLoop = new CraftPattern<>(
                "right", 1, List.of(CraftInput.returnedFrom("seed", 1, rightSource)), "right-loop");
        CraftPattern<String> rightRaw = new CraftPattern<>(
                "right", 1, List.of(CraftInput.of("right-raw", 1)), "right-raw");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("root", 1, List.of(CraftInput.of("left", 1), CraftInput.of("right", 1)))
                .pattern(leftLoop)
                .pattern(leftRaw)
                .pattern(rightLoop)
                .pattern(rightRaw)
                .reusableStock("host", "seed", 1)
                .stock("right-raw", 1)
                .build();

        PlanningResult<String> result = CraftPlannerV2.planDetailed(graph, "root", 1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(1L, result.plan().firings().getOrDefault(leftLoop, 0L));
        assertEquals(0L, result.plan().firings().getOrDefault(rightLoop, 0L));
        assertEquals(1L, result.plan().firings().getOrDefault(rightRaw, 0L));
        assertEquals(1L, result.plan().usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
        assertEquals(1, result.diagnostics().lowWidthSolved());
    }

    @Test
    void contractedMacroByproductFeedsSiblingAfterSeedStartup() {
        var source = new ReusableStockSource("host", "loop");
        CraftPattern<String> macro = new CraftPattern<>(
                "P",
                1,
                List.of(CraftInput.returnedFrom("seed", 1, source)),
                List.of(CraftOutput.of("token", 10)),
                "contracted-loop");
        CraftPattern<String> deadP = new CraftPattern<>(
                "P", 1, List.of(CraftInput.of("dead-p", 1)), "dead-p");
        CraftPattern<String> tokenConsumer = new CraftPattern<>(
                "Q", 1, List.of(CraftInput.of("token", 1)), "token-consumer");
        CraftPattern<String> deadQ = new CraftPattern<>(
                "Q", 1, List.of(CraftInput.of("dead-q", 1)), "dead-q");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("root", 1, List.of(CraftInput.of("P", 1), CraftInput.of("Q", 1)))
                .pattern(macro)
                .pattern(deadP)
                .pattern(tokenConsumer)
                .pattern(deadQ)
                .reusableStock("host", "seed", 1)
                .build();

        PlanningResult<String> result = CraftPlannerV2.planDetailed(graph, "root", 1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(1L, result.plan().firings().getOrDefault(macro, 0L));
        assertEquals(1L, result.plan().firings().getOrDefault(tokenConsumer, 0L));
        assertEquals(0L, result.plan().usedStock().getOrDefault("token", 0L));
        assertEquals(1, result.diagnostics().lowWidthSolved());
    }

    @Test
    void unrelatedCycleCutDoesNotDisableNarrowDagComponents() {
        CraftGraph.Builder<String> builder = twoRouteRecurrence("cut-rec-", 7)
                .stock("cut-rec-1", 2)
                .stock("cut-rec-2", 2)
                .pattern("ring-target", 1, List.of(CraftInput.of("ring-B", 1)))
                .pattern("ring-B", 1, List.of(CraftInput.of("ring-D", 9)))
                .pattern("ring-D", 9, List.of(CraftInput.of("ring-B", 1)))
                .stock("ring-D", 9)
                .pattern("root", 1, List.of(
                        CraftInput.of("cut-rec-6", 1), CraftInput.of("ring-target", 1)));

        PlanningResult<String> result = CraftPlannerV2.planDetailed(builder.build(), "root", 1);

        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertTrue(result.diagnostics().cycleCuts() > 0);
        assertTrue(result.diagnostics().lowWidthSolved() >= 1,
                "a cut in the ring component must not globally veto the recurrence solver");
    }

    private static PlanningResult<String> assertStarvedRecurrence(
            String prefix, int depth, long amount) {
        return org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2), () -> {
                    PlanningResult<String> result = CraftPlannerV2.planDetailed(
                            twoRouteRecurrence(prefix, depth).build(),
                            prefix + (depth - 1),
                            amount);
                    assertFalse(result.plan().feasible());
                    assertFalse(result.plan().budgetExhausted());
                    assertTrue(result.plan().missing().keySet().stream()
                            .allMatch(key -> key.equals(prefix + "0")
                                    || key.equals(prefix + "1")
                                    || key.equals(prefix + "2")));
                    assertEquals(3, result.diagnostics().separatorWidthPeak());
                    assertEquals(1, result.diagnostics().lowWidthInfeasible());
                    assertEquals(0, result.diagnostics().lowWidthAttempts());
                    assertEquals(0, result.diagnostics().dynamicCapacityEvaluations());
                    assertEquals(0, result.diagnostics().consumedFallbackBudget());
                    return result;
                });
    }

    private static CraftGraph.Builder<String> twoRouteRecurrence(String prefix, int depth) {
        return twoRouteRecurrence(prefix, depth, new int[] {1, 1, 1, 1});
    }

    /**
     * A feasible narrow chain whose LP relaxation has near-one fractional coordinates at every
     * level. This is deliberately a solver-shape test, not a production recipe special case.
     */
    private static CraftGraph<String> nearIntegralStatefulChain(int patternCount) {
        return nearIntegralStatefulChain(patternCount, 1L);
    }

    private static CraftGraph<String> nearIntegralStatefulChain(
            int patternCount, long availableRaw) {
        int itemCount = patternCount - 1;
        long[] batches = new long[itemCount];
        for (int i = 0; i < itemCount; i++) {
            batches[i] = 1_000_000_007L + 2L * i;
        }

        ReusableStockSource seedSource =
                new ReusableStockSource("budget-host", "budget-loop");
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        builder.pattern(new CraftPattern<>(
                "budget-x0",
                batches[0],
                List.of(
                        CraftInput.of("budget-x1", batches[1] - 1L),
                        CraftInput.returnedFrom("budget-tool", 1, seedSource)),
                "budget-good-head"));
        builder.pattern(new CraftPattern<>(
                "budget-x0",
                batches[0],
                List.of(
                        CraftInput.of("budget-dead", 1),
                        CraftInput.returnedFrom("budget-tool", 1, seedSource)),
                "budget-dead-head"));
        for (int i = 1; i < itemCount - 1; i++) {
            builder.pattern(new CraftPattern<>(
                    "budget-x" + i,
                    batches[i],
                    List.of(
                            CraftInput.of("budget-x" + (i + 1), batches[i + 1] - 1L),
                            CraftInput.returnedFrom("budget-tool", 1, seedSource)),
                    "budget-chain-" + i));
        }
        builder.pattern(new CraftPattern<>(
                "budget-x" + (itemCount - 1),
                batches[itemCount - 1],
                List.of(
                        CraftInput.of("budget-raw", 1),
                        CraftInput.returnedFrom("budget-tool", 1, seedSource)),
                "budget-chain-tail"));
        builder.pattern("budget-side", 1, List.of(CraftInput.of("budget-side-raw", 1)));
        builder.pattern("budget-side", 1, List.of(CraftInput.of("budget-side-dead", 1)));
        builder.pattern("budget-root", 1, List.of(
                CraftInput.of("budget-x0", batches[0]),
                CraftInput.of("budget-side", 1)));
        return builder
                .reusableStock("budget-host", "budget-tool", 1)
                .stock("budget-side-raw", availableRaw)
                .stock("budget-raw", availableRaw)
                .build();
    }

    private static CraftGraph.Builder<String> twoRouteRecurrence(
            String prefix, int depth, int[] coefficients) {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        appendTwoRouteRecurrence(builder, prefix, null, depth, coefficients);
        return builder;
    }

    private static void appendTwoRouteRecurrence(
            CraftGraph.Builder<String> builder,
            String prefix,
            String sharedBasePrefix,
            int depth,
            int[] coefficients) {
        for (int i = 3; i < depth; i++) {
            builder.pattern(new CraftPattern<>(
                    recurrenceKey(prefix, sharedBasePrefix, i),
                    1,
                    List.of(
                            CraftInput.of(
                                    recurrenceKey(prefix, sharedBasePrefix, i - 1), coefficients[0]),
                            CraftInput.of(
                                    recurrenceKey(prefix, sharedBasePrefix, i - 2), coefficients[1])),
                    "route-1-" + i));
            builder.pattern(new CraftPattern<>(
                    recurrenceKey(prefix, sharedBasePrefix, i),
                    1,
                    List.of(
                            CraftInput.of(
                                    recurrenceKey(prefix, sharedBasePrefix, i - 2), coefficients[2]),
                            CraftInput.of(
                                    recurrenceKey(prefix, sharedBasePrefix, i - 3), coefficients[3])),
                    "route-2-" + i));
        }
    }

    /** Width-three recurrence plus five live top-level leaves: stable separator width is eight. */
    private static void appendWidthEightRecurrence(
            CraftGraph.Builder<String> builder, String prefix) {
        appendTwoRouteRecurrence(builder, prefix, null, 6, new int[] {1, 1, 1, 1});
        List<CraftInput<String>> routeOne = new ArrayList<>();
        List<CraftInput<String>> routeTwo = new ArrayList<>();
        routeOne.add(CraftInput.of(prefix + "5", 1));
        routeOne.add(CraftInput.of(prefix + "4", 1));
        routeTwo.add(CraftInput.of(prefix + "4", 1));
        routeTwo.add(CraftInput.of(prefix + "3", 1));
        for (int i = 0; i < 5; i++) {
            String padding = prefix + "padding-" + i;
            routeOne.add(CraftInput.of(padding, 1));
            routeTwo.add(CraftInput.of(padding, 1));
            builder.stock(padding, 1);
        }
        builder.pattern(new CraftPattern<>(prefix + "6", 1, routeOne, "route-1-6"));
        builder.pattern(new CraftPattern<>(prefix + "6", 1, routeTwo, "route-2-6"));
        builder.stock(prefix + "1", 2).stock(prefix + "2", 2);
    }

    private static String recurrenceKey(String prefix, String sharedBasePrefix, int level) {
        return sharedBasePrefix != null && level < 3
                ? sharedBasePrefix + level
                : prefix + level;
    }

    private static List<Long> baseMissing(CraftPlan<String> plan, String prefix) {
        List<Long> result = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            result.add(plan.missing().getOrDefault(prefix + i, 0L));
        }
        return result;
    }
}
