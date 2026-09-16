package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatRankedFlowSolverTest {
    @BeforeAll
    static void nativeRuntimeLoads() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath(), () -> String.valueOf(
                CpSatIntegerLinearSolver.loadFailure()));
    }

    @Test
    void byproductFeedbackIsSolvedWithoutCallingV2() {
        long amount = 1_000_000_000L;
        var makeB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A-to-B");
        var makeC = new CraftPattern<>(
                "C", 1, List.of(CraftInput.of("A", 1)), "A-to-C");
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("B", 1), CraftInput.of("C", 1)),
                List.of(CraftOutput.of("A", 2)), "B-C-to-T-2A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(makeC).pattern(target).stock("A", 2).build();

        var result = CpSatRankedFlowSolver.solve(graph, "T", amount);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(2L, result.plan().usedStock().get("A"));
        assertEquals(amount, result.plan().firings().get(makeB));
        assertEquals(amount, result.plan().firings().get(makeC));
        assertEquals(amount, result.plan().firings().get(target));
    }

    @Test
    void byproductCannotJustifyAnExtraPrimaryFiring() {
        var makeP = new CraftPattern<>(
                "P", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("B", 1)), "raw-to-P-B");
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("P", 1), CraftInput.of("B", 2)), "P-2B-to-T");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeP).pattern(target).stock("raw", 2).build();

        var result = CpSatRankedFlowSolver.solve(graph, "T", 1);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(!result.plan().feasible());
        assertEquals(1L, result.plan().firings().get(makeP));
        assertEquals(1L, result.plan().missing().get("B"));
    }

    @Test
    void onePartialPrimaryBatchMayStillSupplyItsByproduct() {
        var makeP = new CraftPattern<>(
                "P", 4, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("B", 1)), "raw-to-4P-B");
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("P", 1), CraftInput.of("B", 1)), "P-B-to-T");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeP).pattern(target).stock("raw", 1).build();

        var result = CpSatRankedFlowSolver.solve(graph, "T", 1);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(1L, result.plan().firings().get(makeP));
    }

    @Test
    void finiteUseCarrierCanBeCraftedAsTheNextDurabilityChainUnit() {
        var makeTool = new CraftPattern<>(
                "tool", 1, List.of(CraftInput.of("iron", 1)), "iron-to-tool");
        var product = new CraftPattern<>(
                "product", 1,
                List.of(CraftInput.of("raw", 1), CraftInput.finiteUse("tool", 1, 10)),
                "raw-with-tool");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeTool).pattern(product)
                .stock("tool", 1).stock("iron", 1).stock("raw", 11).build();

        var result = CpSatRankedFlowSolver.solve(graph, "product", 11);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(11L, result.plan().firings().get(product));
        assertEquals(1L, result.plan().firings().get(makeTool));
        assertEquals(1L, result.plan().usedStock().get("tool"));
        assertEquals(1L, result.plan().usedStock().get("iron"));
    }

    @Test
    void overlappingFuzzyReusableRoutesCannotDoubleSpendOnePhysicalVariant() {
        var sourceA = new ReusableStockSource("host", "shared", "route-a");
        var sourceB = new ReusableStockSource("host", "shared", "route-b");
        CraftGraph<String> graph = fuzzyReusableGraph(sourceA, sourceB, 1, 0, false);

        var result = CpSatRankedFlowSolver.solve(graph, "result", 1);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(!result.plan().feasible());
        assertEquals(1L, result.plan().missing().values().stream()
                .mapToLong(Long::longValue).sum());
        assertEquals(1L, result.plan().usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
    }

    @Test
    void fuzzyReusableAllocationReassignsFlexibleRouteAroundConstrainedRoute() {
        var sourceA = new ReusableStockSource("host", "shared", "route-a");
        var sourceB = new ReusableStockSource("host", "shared", "route-b");

        for (boolean reverse : new boolean[] {false, true}) {
            CraftGraph<String> graph = fuzzyReusableGraph(sourceA, sourceB, 1, 1, reverse);

            var result = CpSatRankedFlowSolver.solve(graph, "result", 1);

            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
            assertEquals(1L, result.plan().usedReusableStock().get(
                    new ReusableStockUsageKey<>(
                            "host", "shared", "route-a", "A", "Y")));
            assertEquals(1L, result.plan().usedReusableStock().get(
                    new ReusableStockUsageKey<>(
                            "host", "shared", "route-b", "B", "X")));
        }
    }

    @Test
    void recipesSharingOneReusableRouteNeedOnlyTheLargestSeed() {
        var shared = new ReusableStockSource("host", "shared-loop", "shared-route");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(
                        CraftInput.of("left", 1), CraftInput.of("right", 1)))
                .pattern("left", 1, List.of(CraftInput.returnedFrom("seed", 1, shared)))
                .pattern("right", 1, List.of(CraftInput.returnedFrom("seed", 1, shared)))
                .reusableStock("host", "seed", 1)
                .build();

        var result = CpSatRankedFlowSolver.solve(graph, "result", 1);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(1L, result.plan().usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
    }

    @Test
    void dedicatedPoolsWithTheSameLogicalKeyCompeteForPhysicalCapacity() {
        var left = new ReusableStockSource("host", "left-loop");
        var right = new ReusableStockSource("host", "right-loop");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(
                        CraftInput.of("left", 1), CraftInput.of("right", 1)))
                .pattern("left", 1, List.of(CraftInput.returnedFrom("seed", 1, left)))
                .pattern("right", 1, List.of(CraftInput.returnedFrom("seed", 1, right)))
                .reusableStock("host", "seed", 1)
                .build();

        var result = CpSatRankedFlowSolver.solve(graph, "result", 1);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(!result.plan().feasible());
        assertEquals(1L, result.plan().missing().get("seed"));
        assertEquals(1L, result.plan().usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
    }

    private static CraftGraph<String> fuzzyReusableGraph(
            ReusableStockSource sourceA,
            ReusableStockSource sourceB,
            long sharedX,
            long onlyA,
            boolean reverse) {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        if (reverse) {
            builder.pattern("result", 1, List.of(
                    CraftInput.of("B-product", 1), CraftInput.of("A-product", 1)))
                    .pattern("B-product", 1,
                            List.of(CraftInput.returnedFrom("B", 1, sourceB)))
                    .pattern("A-product", 1,
                            List.of(CraftInput.returnedFrom("A", 1, sourceA)));
        } else {
            builder.pattern("result", 1, List.of(
                    CraftInput.of("A-product", 1), CraftInput.of("B-product", 1)))
                    .pattern("A-product", 1,
                            List.of(CraftInput.returnedFrom("A", 1, sourceA)))
                    .pattern("B-product", 1,
                            List.of(CraftInput.returnedFrom("B", 1, sourceB)));
        }
        return builder
                .reusableStock("host", "X", sharedX)
                .reusableStock("host", "Y", onlyA)
                .reusableStockRoute(sourceA, "A", List.of("X", "Y"))
                .reusableStockRoute(sourceB, "B", List.of("X"))
                .build();
    }

    @Test
    void rankedSupportChoosesOneDirectionAtLongScale() {
        long amount = 1_000_000_000_000L;
        var makeB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A-to-B");
        var makeA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 1)), "B-to-A");
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), "target");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(makeA).pattern(target).stock("A", 2L * amount).build();

        var result = CpSatRankedFlowSolver.solve(graph, "T", amount);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status(),
                () -> String.valueOf(CpSatIntegerLinearSolver.loadFailure()));
        assertTrue(result.plan().feasible());
        assertEquals(2L * amount, result.plan().usedStock().get("A"));
        assertEquals(amount, result.plan().firings().get(makeB));
        assertEquals(0L, result.plan().firings().getOrDefault(makeA, 0L));
        assertEquals(amount, result.plan().firings().get(target));
    }

    @Test
    void executionMinimizationUsesTheExistingIntermediate() {
        var makeX = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("raw", 1)), "raw-to-X");
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("X", 1)), "X-to-T");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeX)
                .pattern(target)
                .stock("X", 1)
                .stock("raw", 1)
                .build();

        var result = CpSatRankedFlowSolver.solve(graph, "T", 1);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status(),
                () -> "branches=" + result.branches());
        assertTrue(result.plan().feasible());
        // Using the existing X removes one recipe firing, so it wins before the stock tie-break.
        assertEquals(1L, result.plan().usedStock().get("X"));
        assertEquals(0L, result.plan().usedStock().getOrDefault("raw", 0L));
        assertEquals(0L, result.plan().firings().getOrDefault(makeX, 0L));
        assertEquals(1L, result.plan().firings().get(target));
    }

    @Test
    void sameTierStockMinimizationPrefersTwoForOneOverThreeForOne() {
        var efficient = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("A", 2)), "2A-to-X");
        var wasteful = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("A", 3)), "3A-to-X");
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("X", 1)), "X-to-T");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(efficient)
                .pattern(wasteful)
                .pattern(target)
                .stock("A", 3)
                .build();

        var result = CpSatRankedFlowSolver.solve(graph, "T", 1);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible());
        assertEquals(2L, result.plan().usedStock().get("A"));
        assertEquals(1L, result.plan().firings().get(efficient));
        assertEquals(0L, result.plan().firings().getOrDefault(wasteful, 0L));
    }

    @Test
    void batchedRouteBecomesCheaperAtTheSecondRequestedUnit() {
        var direct = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("near", 1)), "near-to-T");
        var makeBatch = new CraftPattern<>(
                "T", 4, List.of(CraftInput.of("batch", 1)), "batch-to-4T");
        var makeBatchInput = new CraftPattern<>(
                "batch", 1, List.of(CraftInput.of("raw", 1)), "raw-to-batch");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(direct)
                .pattern(makeBatch)
                .pattern(makeBatchInput)
                .stock("near", 4)
                .stock("raw", 1)
                .build();

        var one = CpSatRankedFlowSolver.solve(graph, "T", 1);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, one.status());
        assertEquals(1L, one.plan().firings().get(direct));
        assertEquals(0L, one.plan().firings().getOrDefault(makeBatch, 0L));

        var two = CpSatRankedFlowSolver.solve(graph, "T", 2);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, two.status());
        assertEquals(0L, two.plan().firings().getOrDefault(direct, 0L));
        assertEquals(1L, two.plan().firings().get(makeBatch));
        assertEquals(1L, two.plan().firings().get(makeBatchInput));
        assertEquals(1L, two.plan().usedStock().get("raw"));
    }

    @Test
    void layeredFibonacciCoefficientTradeoffPrefersFewerExecutions() {
        int depth = 32;
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (int i = 3; i <= depth; i++) {
            builder.pattern(new CraftPattern<>(
                    "X" + i,
                    1,
                    List.of(
                            CraftInput.of("X" + (i - 1), 1),
                            CraftInput.of("X" + (i - 2), 1)),
                    "fib-" + i));
            builder.pattern(new CraftPattern<>(
                    "X" + i,
                    1,
                    List.of(
                            CraftInput.of("X" + (i - 2), 3),
                            CraftInput.of("X" + (i - 3), 1)),
                    "heavy-skip-" + i));
        }
        CraftGraph<String> graph = builder
                .stock("X0", 3_000_000L)
                .stock("X1", 3_000_000L)
                .stock("X2", 3_000_000L)
                .build();

        var solved = CpSatRankedFlowSolver.solve(graph, "X" + depth, 1);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status(),
                () -> "branches=" + solved.branches());
        var result = solved.plan();

        assertTrue(result.feasible(), () -> "missing=" + result.missing());
        long executions = result.firings().values().stream().mapToLong(Long::longValue).sum();
        long used = result.usedStock().values().stream().mapToLong(Long::longValue).sum();
        // The old used-first optimum is (2,178,308 executions, 2,178,309 used). The new order
        // deliberately accepts more leaves to reduce the actual work by about 23.6 percent.
        assertEquals(1_664_079L, executions);
        assertEquals(2_692_538L, used);
    }

    @Test
    void originalMultiLayerFibonacciScalesLinearlyWithRequestQuantity() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            int depth = 32;
            CraftGraph.Builder<String> builder = CraftGraph.builder();
            for (int i = 3; i <= depth; i++) {
                builder.pattern(new CraftPattern<>(
                        "X" + i,
                        1,
                        List.of(
                                CraftInput.of("X" + (i - 1), 1),
                                CraftInput.of("X" + (i - 2), 1)),
                        "near-fib-" + i));
                builder.pattern(new CraftPattern<>(
                        "X" + i,
                        1,
                        List.of(
                                CraftInput.of("X" + (i - 2), 1),
                                CraftInput.of("X" + (i - 3), 1)),
                        "skip-fib-" + i));
            }
            CraftGraph<String> graph = builder
                    .stock("X0", 10_000_000L)
                    .stock("X1", 10_000_000L)
                    .stock("X2", 10_000_000L)
                    .build();

            for (long amount : new long[] {1L, 1_000L}) {
                var solved = CpSatRankedFlowSolver.solve(graph, "X" + depth, amount);
                assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status(),
                        () -> "amount=" + amount + ", branches=" + solved.branches());
                assertTrue(solved.plan().feasible());
                long executions = solved.plan().firings().values().stream()
                        .mapToLong(Long::longValue).sum();
                long used = solved.plan().usedStock().values().stream()
                        .mapToLong(Long::longValue).sum();
                assertEquals(5_841L * amount, executions);
                assertEquals(5_842L * amount, used);
            }
        });
    }

    @Test
    void nonBalancedEquivalentConversionMayUseBothDirectionsForBatchResidue() {
        long amount = 1_000_000_000L; // amount % 3 == 1 leaves exactly two reverse firings useful
        var makeNineB = new CraftPattern<>(
                "B", 9, List.of(CraftInput.of("A", 3)), "3A-to-9B");
        var makeA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 3)), "3B-to-A");
        var makeC = new CraftPattern<>(
                "C", 1,
                List.of(CraftInput.of("A", 2), CraftInput.of("B", 3)),
                "2A-3B-to-C");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeNineB)
                .pattern(makeA)
                .pattern(makeC)
                .stock("A", 3L * amount)
                .build();

        var direct = CpSatRankedFlowSolver.solve(graph, "C", amount);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, direct.status(),
                () -> "branches=" + direct.branches() + ", failure="
                        + CpSatIntegerLinearSolver.loadFailure());
        assertTrue(direct.plan().feasible());
        assertEquals((amount + 2L) / 3L, direct.plan().firings().get(makeNineB));
        assertEquals(2L, direct.plan().firings().get(makeA));
        assertEquals(amount, direct.plan().firings().get(makeC));
        assertEquals(3L * amount, direct.plan().usedStock().get("A"));

    }

    @Test
    void aggregateBalanceCannotBypassConversionCycleStartupPrefix() {
        var makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 2)), "2A-to-2B");
        var makeA = new CraftPattern<>(
                "A", 3, List.of(CraftInput.of("B", 3)), "3B-to-3A");
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), "target");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB)
                .pattern(makeA)
                .pattern(target)
                .stock("A", 2)
                .build();

        var result = CpSatRankedFlowSolver.solve(graph, "T", 1);

        // The aggregate equations admit (makeB=2, makeA=1), but after one makeB firing the
        // marking is A=0,B=2 and neither transition can continue. The prefix certificate therefore
        // rejects that false positive and the direct planner reports the one real startup shortfall.
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(!result.plan().feasible());
        assertEquals(java.util.Map.of("B", 1L), result.plan().missing());
    }

    @Test
    void independentEquivalentCyclesReceiveIndependentPrefixCertificates() {
        var makeNineB = new CraftPattern<>(
                "B", 9, List.of(CraftInput.of("A", 3)), "3A-to-9B");
        var makeA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 3)), "3B-to-A");
        var makeNineE = new CraftPattern<>(
                "E", 9, List.of(CraftInput.of("D", 3)), "3D-to-9E");
        var makeD = new CraftPattern<>(
                "D", 1, List.of(CraftInput.of("E", 3)), "3E-to-D");
        var target = new CraftPattern<>(
                "T", 1,
                List.of(
                        CraftInput.of("A", 2), CraftInput.of("B", 3),
                        CraftInput.of("D", 2), CraftInput.of("E", 3)),
                "target");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeNineB).pattern(makeA)
                .pattern(makeNineE).pattern(makeD)
                .pattern(target)
                .stock("A", 3).stock("D", 3)
                .build();

        var result = CpSatRankedFlowSolver.solve(graph, "T", 1);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status(),
                () -> "branches=" + result.branches());
        assertTrue(result.plan().feasible());
        assertEquals(1L, result.plan().firings().get(makeNineB));
        assertEquals(2L, result.plan().firings().get(makeA));
        assertEquals(1L, result.plan().firings().get(makeNineE));
        assertEquals(2L, result.plan().firings().get(makeD));
        assertEquals(1L, result.plan().firings().get(target));
    }

    @Test
    void catalyzedConversionUsesPresenceAndOrdinaryBalanceConstraints() {
        long amount = 1_000_000_000L;
        var makeB = new CraftPattern<>(
                "B", 1,
                List.of(
                        CraftInput.of("A", 1),
                        CraftInput.of("fuel", 2),
                        CraftInput.returned("template", 1)),
                "catalyzed-A-to-B");
        var makeA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 1)), "B-to-A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB)
                .pattern(makeA)
                .stock("A", amount)
                .stock("fuel", 2L * amount)
                .stock("template", 1)
                .build();

        var direct = CpSatRankedFlowSolver.solve(graph, "B", amount);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, direct.status(),
                () -> "branches=" + direct.branches() + ", failure="
                        + CpSatIntegerLinearSolver.loadFailure());
        var result = direct.plan();

        assertTrue(result.feasible(), () -> "missing=" + result.missing());
        assertEquals(amount, result.firings().get(makeB));
        assertEquals(0L, result.firings().getOrDefault(makeA, 0L));
        assertEquals(amount, result.usedStock().get("A"));
        assertEquals(2L * amount, result.usedStock().get("fuel"));
        assertEquals(1L, result.usedStock().get("template"));
    }

    @Test
    void catalystMayBeProducedByAnEarlierRankedBatch() {
        long amount = 1_000_000_000L;
        var makeTemplate = new CraftPattern<>(
                "template", 1, List.of(CraftInput.of("template-base", 1)), "make-template");
        var makeB = new CraftPattern<>(
                "B", 1,
                List.of(CraftInput.of("A", 1), CraftInput.returned("template", 1)),
                "catalyzed-A-to-B");
        var makeA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 1)), "B-to-A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeTemplate)
                .pattern(makeB)
                .pattern(makeA)
                .stock("A", amount)
                .stock("template-base", 1)
                .build();

        var direct = CpSatRankedFlowSolver.solve(graph, "B", amount);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, direct.status(),
                () -> "failure=" + CpSatIntegerLinearSolver.loadFailure());
        var result = direct.plan();

        assertTrue(result.feasible(), () -> "missing=" + result.missing());
        assertEquals(1L, result.firings().get(makeTemplate));
        assertEquals(amount, result.firings().get(makeB));
        assertEquals(1L, result.usedStock().get("template-base"));
        assertEquals(0L, result.usedStock().getOrDefault("template", 0L));
    }

    @Test
    void gainfulConversionCanSelectOnlyTheNeededDirection() {
        long amount = 1_000_000_000_000L;
        var makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 1)), "A-to-2B");
        var makeA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 1)), "B-to-A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB)
                .pattern(makeA)
                .stock("A", amount / 2)
                .build();

        var result = CpSatRankedFlowSolver.solve(graph, "B", amount);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(amount / 2, result.plan().firings().get(makeB));
        assertEquals(amount / 2, result.plan().usedStock().get("A"));
        assertEquals(0L, result.plan().firings().getOrDefault(makeA, 0L));
    }
}
