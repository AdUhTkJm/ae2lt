package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Structural pressure cases that invoke only the independent CP-SAT graph solver. */
class CpSatComplexCycleAdversarialTest {
    private static final Duration LIMIT = Duration.ofSeconds(3);

    @BeforeAll
    static void nativeRuntimeLoads() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath(), () -> String.valueOf(
                CpSatIntegerLinearSolver.loadFailure()));
    }

    @Test
    void eightTierOverlappingConversionRingsScaleAtTrillion() {
        long amount = 1_000_000_000_000L;
        var forward = new ArrayList<CraftPattern<String>>();
        var builder = CraftGraph.<String>builder();

        for (int tier = 0; tier < 7; tier++) {
            var up = new CraftPattern<>(
                    "S" + (tier + 1), 1,
                    List.of(CraftInput.of("S" + tier, 1)), "up-" + tier);
            var down = new CraftPattern<>(
                    "S" + tier, 1,
                    List.of(CraftInput.of("S" + (tier + 1), 1)), "down-" + tier);
            forward.add(up);
            builder.pattern(up).pattern(down);
        }
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("S0", 1), CraftInput.of("S7", 1)), "target");
        CraftGraph<String> graph = builder.pattern(target).stock("S0", 2L * amount).build();

        var solved = assertTimeoutPreemptively(
                LIMIT, () -> CpSatRankedFlowSolver.solve(graph, "T", amount));

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status(),
                () -> "branches=" + solved.branches());
        CraftPlan<String> plan = solved.plan();
        assertTrue(plan.feasible(), () -> "missing=" + plan.missing());
        assertEquals(2L * amount, plan.usedStock().get("S0"));
        assertEquals(amount, firings(plan, target));
        for (var pattern : forward) {
            assertEquals(amount, firings(plan, pattern), () -> pattern.toString());
        }
    }

    @Test
    void complexByproductFeedbackUsesTheIndependentPetriMacro() {
        long amount = 1_000_000_000L;
        var split = new CraftPattern<>(
                "B", 3, List.of(CraftInput.of("A", 2)),
                List.of(CraftOutput.of("C", 1)), "2A-to-3B-C");
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("B", 3), CraftInput.of("C", 1)),
                List.of(CraftOutput.of("A", 1)), "3B-C-to-T-A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(split).pattern(target).stock("A", amount + 1L).build();

        var solved = assertTimeoutPreemptively(
                LIMIT, () -> CpSatRankedFlowSolver.solve(graph, "T", amount));

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status(),
                () -> "branches=" + solved.branches());
        assertTrue(solved.plan().feasible(), () -> "missing=" + solved.plan().missing());
        assertEquals(amount + 1L, solved.plan().usedStock().get("A"));
        assertEquals(amount, firings(solved.plan(), split));
        assertEquals(amount, firings(solved.plan(), target));
    }

    @Test
    void internalReturnedCatalystBecomesAPetriReadArc() {
        long amount = 1_000_000_000L;
        var makeB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A-to-B");
        var target = new CraftPattern<>(
                "T", 1,
                List.of(CraftInput.of("B", 1), CraftInput.returned("A", 1)),
                List.of(CraftOutput.of("A", 1)), "B-plus-read-A-to-T-A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(target).stock("A", 2).build();

        var solved = assertTimeoutPreemptively(
                LIMIT, () -> CpSatRankedFlowSolver.solve(graph, "T", amount));

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status(),
                () -> "branches=" + solved.branches());
        assertTrue(solved.plan().feasible(), () -> "missing=" + solved.plan().missing());
        assertEquals(2L, solved.plan().usedStock().get("A"));
        assertEquals(amount, firings(solved.plan(), makeB));
        assertEquals(amount, firings(solved.plan(), target));
    }

    @Test
    void weightedLossyFeedbackUsesClosedFormPrefixAtBillionScale() {
        long amount = 1_000_000_000L;
        long minimumSeed = Sat.add(Sat.ceilDiv(amount, 2L), 5L);
        var makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 3)), "3A-to-2B");
        var target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("B", 3)),
                List.of(CraftOutput.of("A", 4)), "3B-to-T-4A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(target).stock("A", minimumSeed).build();

        var solved = assertTimeoutPreemptively(
                LIMIT, () -> CpSatRankedFlowSolver.solve(graph, "T", amount));

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status(),
                () -> "branches=" + solved.branches());
        assertTrue(solved.plan().feasible(), () -> "missing=" + solved.plan().missing());
        assertEquals(minimumSeed, solved.plan().usedStock().get("A"));
        assertEquals(1_500_000_000L, firings(solved.plan(), makeB));
        assertEquals(amount, firings(solved.plan(), target));
    }

    @Test
    void unprovenPositiveByproductLoopCannotBootstrapItself() {
        var makeB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)),
                List.of(CraftOutput.of("C", 1)), "A-to-B-C");
        var duplicateA = new CraftPattern<>(
                "A", 2, List.of(CraftInput.of("C", 1)), "C-to-2A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(duplicateA).stock("A", 1).build();

        var solved = CpSatRankedFlowSolver.solve(graph, "B", 3);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status());
        assertTrue(!solved.plan().feasible(), "positive raw feedback is not a proven macro");
        assertTrue(!solved.plan().missing().isEmpty());
    }

    @Test
    void containerRemainderIsAnOrdinaryByproductFeedbackState() {
        var use = new CraftPattern<>(
                "T", 1,
                List.of(CraftInput.consumedReturning("full", 1, "empty")),
                "use-full-return-empty");
        var refill = new CraftPattern<>(
                "full", 1,
                List.of(CraftInput.of("empty", 1), CraftInput.of("water", 1)),
                "refill");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(use).pattern(refill).stock("full", 1).stock("water", 99).build();

        var solved = CpSatRankedFlowSolver.solve(graph, "T", 100);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status(),
                () -> "branches=" + solved.branches());
        assertTrue(solved.plan().feasible(), () -> "missing=" + solved.plan().missing());
        assertEquals(1L, solved.plan().usedStock().get("full"));
        assertEquals(99L, solved.plan().usedStock().get("water"));
        assertEquals(100L, firings(solved.plan(), use));
        assertEquals(99L, firings(solved.plan(), refill));
    }

    @Test
    void containerCycleReportsOneExecutableBootstrapState() {
        var use = new CraftPattern<>(
                "T", 1,
                List.of(CraftInput.consumedReturning("full", 1, "empty")),
                "use-full-return-empty");
        var refill = new CraftPattern<>(
                "full", 1,
                List.of(CraftInput.of("empty", 1), CraftInput.of("water", 1)),
                "refill");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(use).pattern(refill).stock("water", 100).build();

        var first = CpSatRankedFlowSolver.solve(graph, "T", 100);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, first.status());
        assertTrue(!first.plan().feasible());
        assertEquals(1L, first.plan().missing().values().stream().mapToLong(Long::longValue).sum());
        var refilled = CpSatRankedFlowSolver.solve(
                graph.withAdditionalStock(first.plan().missing()), "T", 100);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, refilled.status());
        assertTrue(refilled.plan().feasible(), () -> "missing=" + refilled.plan().missing());
    }

    private static long firings(CraftPlan<String> plan, CraftPattern<String> pattern) {
        return plan.firings().getOrDefault(pattern, 0L);
    }
}
