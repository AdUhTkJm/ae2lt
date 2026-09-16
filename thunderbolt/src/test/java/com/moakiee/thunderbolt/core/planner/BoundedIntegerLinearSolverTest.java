package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class BoundedIntegerLinearSolverTest {

    @Test
    void solvesFixedLongScaleWithoutEnumeratingQuantity() {
        long requested = 1_000_000_000_000L;
        var result = BoundedIntegerLinearSolver.solve(2, List.of(
                row(requested, 1, 1),
                row(-requested, -1, -1),
                row(requested / 3, 1, 0)), Sat.SAT, 16);

        assertTrue(result.solved(), () -> "status=" + result.status());
        assertArrayEquals(new long[] {requested / 3, requested - requested / 3}, result.values());
        assertEquals(1, result.visitedNodes());
    }

    @Test
    void branchesOnFractionalRelaxationAndProvesParityInfeasible() {
        var result = BoundedIntegerLinearSolver.solve(1, List.of(
                row(3, 2),
                row(-3, -2)), Sat.SAT, 16);

        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status());
        assertTrue(result.visitedNodes() <= 3);
    }

    @Test
    void reportsBudgetInsteadOfEnumeratingIntegerDomain() {
        var result = BoundedIntegerLinearSolver.solve(1, List.of(
                row(3, 2),
                row(-3, -2)), Sat.SAT, 1);

        assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, result.status());
        assertEquals(1, result.visitedNodes());
    }

    @Test
    void rejectsDenseRelaxationBeforeAllocatingOrSearchingIt() {
        var budget = BoundedIntegerLinearSolver.WorkBudget.bounded(
                32,
                1_024,
                1_000_000_000L);
        var result = BoundedIntegerLinearSolver.solve(
                4,
                List.of(
                        row(1, 1, 0, 0, 0),
                        row(1, 0, 1, 0, 0),
                        row(1, 0, 0, 1, 0),
                        row(1, 0, 0, 0, 1)),
                Sat.SAT,
                64,
                budget);

        assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, result.status());
        assertEquals(0, result.visitedNodes(),
                "tableau-shape admission must run before branch-and-bound");
    }

    @Test
    void randomSmallSignedSystemsMatchBruteForce() {
        Random random = new Random(0x5EEDB0A7L);
        for (int sample = 0; sample < 64; sample++) {
            int sampleIndex = sample;
            int variables = 1 + random.nextInt(3);
            int rows = 1 + random.nextInt(5);
            int max = 5;
            var constraints = new java.util.ArrayList<BoundedIntegerLinearSolver.Constraint>();
            for (int row = 0; row < rows; row++) {
                long[] coefficients = new long[variables];
                boolean nonZero = false;
                for (int variable = 0; variable < variables; variable++) {
                    coefficients[variable] = random.nextInt(7) - 3;
                    nonZero |= coefficients[variable] != 0;
                }
                if (!nonZero) {
                    coefficients[random.nextInt(variables)] = 1;
                }
                constraints.add(new BoundedIntegerLinearSolver.Constraint(
                        coefficients, random.nextInt(16) - 5));
            }

            long[] brute = bruteForce(variables, max, constraints);
            var solved = BoundedIntegerLinearSolver.solve(
                    variables, constraints, max, 512);
            assertEquals(brute != null, solved.solved(),
                    () -> "sample=" + sampleIndex + " status=" + solved.status());
            if (solved.solved()) {
                assertTrue(feasible(solved.values(), constraints));
            } else {
                assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, solved.status());
            }
        }
    }

    private static long[] bruteForce(
            int variables,
            int max,
            List<BoundedIntegerLinearSolver.Constraint> constraints) {
        long[] values = new long[variables];
        while (true) {
            if (feasible(values, constraints)) {
                return values.clone();
            }
            int variable = 0;
            while (variable < variables && values[variable] == max) {
                values[variable] = 0;
                variable++;
            }
            if (variable == variables) {
                return null;
            }
            values[variable]++;
        }
    }

    private static boolean feasible(
            long[] values, List<BoundedIntegerLinearSolver.Constraint> constraints) {
        for (var constraint : constraints) {
            long total = 0;
            long[] coefficients = constraint.coefficients();
            for (int i = 0; i < values.length; i++) {
                total += coefficients[i] * values[i];
            }
            if (total < constraint.minimum()) {
                return false;
            }
        }
        return true;
    }

    private static BoundedIntegerLinearSolver.Constraint row(long minimum, long... coefficients) {
        return new BoundedIntegerLinearSolver.Constraint(coefficients, minimum);
    }
}
