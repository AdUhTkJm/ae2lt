package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatIntegerLinearSolverTest {

    @BeforeAll
    static void nativeRuntimeLoads() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath(), () -> String.valueOf(
                CpSatIntegerLinearSolver.loadFailure()));
    }

    @Test
    void solvesTrillionScaleIntegerDomainWithoutRadixEncoding() {
        var result = CpSatIntegerLinearSolver.solve(
                1,
                List.of(new BoundedIntegerLinearSolver.Constraint(
                        new long[]{1L}, 1_000_000_000_000L)),
                Sat.SAT);

        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status(),
                () -> String.valueOf(CpSatIntegerLinearSolver.loadFailure()));
        assertEquals(1_000_000_000_000L, result.values()[0]);
    }

    @Test
    void executionMinimizationUsesTheExistingIntermediate() {
        // x crafts one near-root X from one deeper raw. Inventory already contains either item, so
        // minimizing recipe firings first must choose x=0 and consume X directly.
        var result = CpSatIntegerLinearSolver.solve(
                1,
                List.of(new BoundedIntegerLinearSolver.Constraint(new long[] {-1L}, -1L)),
                1L,
                1,
                new CpSatIntegerLinearSolver.StockObjective(
                        new long[][] {{1L}, {-1L}},
                        new long[] {1L, 0L},
                        new long[] {1L, 1L},
                        new int[] {1, 2},
                        new int[0],
                        new int[0]));

        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status());
        assertEquals(0L, result.values()[0]);
    }

    @Test
    void provesSmallBoundedIntegerSystemInfeasible() {
        var result = CpSatIntegerLinearSolver.solve(
                1,
                List.of(
                        new BoundedIntegerLinearSolver.Constraint(new long[]{1L}, 5L),
                        new BoundedIntegerLinearSolver.Constraint(new long[]{-1L}, -4L)),
                100L);

        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status(),
                () -> String.valueOf(CpSatIntegerLinearSolver.loadFailure()));
    }

    @Test
    void findsGlobalCombinationThatGreedyChoiceMisses() {
        // Both inequalities must be considered globally; the minimum total firing count is seven.
        var result = CpSatIntegerLinearSolver.solve(
                2,
                List.of(
                        new BoundedIntegerLinearSolver.Constraint(new long[]{2L, 1L}, 11L),
                        new BoundedIntegerLinearSolver.Constraint(new long[]{1L, 3L}, 12L)),
                100L);

        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status(),
                () -> String.valueOf(CpSatIntegerLinearSolver.loadFailure()));
        assertEquals(7L, result.values()[0] + result.values()[1]);
        assertTrue(2L * result.values()[0] + result.values()[1] >= 11L);
        assertTrue(result.values()[0] + 3L * result.values()[1] >= 12L);
    }
}
