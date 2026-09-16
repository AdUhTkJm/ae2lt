package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PositiveIntegerLinearSolverTest {

    @Test
    void unconstrainedSystemUsesOneCopyOfEveryMember() {
        var result = PositiveIntegerLinearSolver.solve(3, List.of());

        assertEquals(PositiveIntegerLinearSolver.Status.SOLVED, result.status());
        assertArrayEquals(new long[] {1, 1, 1}, result.coefficients());
    }

    @Test
    void solvesRatioBeyondTheLegacyCoefficientBound() {
        var result = solve(2,
                constraint(0, -9, 1),
                constraint(1, 10, -1));

        assertEquals(PositiveIntegerLinearSolver.Status.SOLVED, result.status());
        assertArrayEquals(new long[] {1, 9}, result.coefficients());
    }

    @Test
    void sixVariableSolutionIsNotCutOffByAProbeBudget() {
        var constraints = new ArrayList<PositiveIntegerLinearSolver.Constraint>();
        constraints.add(constraint(0, -3, 2, 0, 0, 0, 0));
        constraints.add(constraint(0, 3, -2, 0, 0, 0, 0));
        for (int variable = 2; variable < 6; variable++) {
            var lower = new long[6];
            lower[0] = -4;
            lower[variable] = 1;
            constraints.add(new PositiveIntegerLinearSolver.Constraint(lower, 0));
            var upper = new long[6];
            upper[0] = 4;
            upper[variable] = -1;
            constraints.add(new PositiveIntegerLinearSolver.Constraint(upper, 0));
        }
        constraints.add(new PositiveIntegerLinearSolver.Constraint(
                new long[] {1, 0, 0, 0, 0, 0}, 1));

        var result = PositiveIntegerLinearSolver.solve(6, constraints);

        assertEquals(PositiveIntegerLinearSolver.Status.SOLVED, result.status());
        assertArrayEquals(new long[] {2, 3, 8, 8, 8, 8}, result.coefficients());
    }

    @Test
    void provesContradictorySystemInfeasible() {
        var result = solve(2,
                constraint(1, 1, -1),
                constraint(1, -1, 1));

        assertEquals(PositiveIntegerLinearSolver.Status.INFEASIBLE, result.status());
    }

    @Test
    void reportsWhenTheExactSolutionCannotFitCraftingCounters() {
        var result = solve(3,
                constraint(0, -Long.MAX_VALUE, 1, 0),
                constraint(0, 0, -2, 1));

        assertEquals(PositiveIntegerLinearSolver.Status.COEFFICIENT_OVERFLOW, result.status());
    }

    @Test
    void generatedFeasibleSystemsStayFeasibleAfterExactSolvingAndReduction() {
        var random = new Random(0x5448554E444552L);
        for (int sample = 0; sample < 1_000; sample++) {
            int variables = 1 + random.nextInt(6);
            var witness = new long[variables];
            for (int i = 0; i < variables; i++) witness[i] = 1 + random.nextInt(20);
            var constraints = new ArrayList<PositiveIntegerLinearSolver.Constraint>();
            int rows = 1 + random.nextInt(12);
            for (int row = 0; row < rows; row++) {
                var coefficients = new long[variables];
                long atWitness = 0;
                for (int i = 0; i < variables; i++) {
                    coefficients[i] = random.nextInt(15) - 7;
                    atWitness += coefficients[i] * witness[i];
                }
                if (atWitness < 0) {
                    long correction = (-atWitness + witness[0] - 1) / witness[0];
                    coefficients[0] += correction;
                    atWitness += correction * witness[0];
                }
                long slack = atWitness == 0 ? 0 : random.nextLong(atWitness + 1);
                constraints.add(new PositiveIntegerLinearSolver.Constraint(
                        coefficients, atWitness - slack));
            }

            var result = PositiveIntegerLinearSolver.solve(variables, constraints);

            assertEquals(PositiveIntegerLinearSolver.Status.SOLVED, result.status(),
                    "sample=" + sample + " witness=" + java.util.Arrays.toString(witness)
                            + " constraints=" + constraints.stream()
                            .map(value -> java.util.Arrays.toString(value.coefficients())
                                    + ">=" + value.minimum())
                            .toList());
            assertFeasible(result.coefficients(), constraints, sample);
        }
    }

    @Test
    void publicRecordsDoNotExposeMutableCoefficientArrays() {
        var source = new long[] {1, 2};
        var constraint = new PositiveIntegerLinearSolver.Constraint(source, 0);
        source[0] = 99;
        var exposed = constraint.coefficients();
        exposed[1] = 99;

        assertArrayEquals(new long[] {1, 2}, constraint.coefficients());

        var solved = PositiveIntegerLinearSolver.solve(2, List.of(constraint));
        var solution = solved.coefficients();
        solution[0] = 99;
        assertTrue(solved.coefficients()[0] > 0);
        assertTrue(solved.coefficients()[0] != 99);
    }

    private static PositiveIntegerLinearSolver.Result solve(
            int variables, PositiveIntegerLinearSolver.Constraint... constraints) {
        return PositiveIntegerLinearSolver.solve(variables, List.of(constraints));
    }

    private static PositiveIntegerLinearSolver.Constraint constraint(
            long minimum, long... coefficients) {
        return new PositiveIntegerLinearSolver.Constraint(coefficients, minimum);
    }

    private static void assertFeasible(
            long[] values, List<PositiveIntegerLinearSolver.Constraint> constraints, int sample) {
        for (var value : values) assertTrue(value > 0, "sample=" + sample);
        for (var constraint : constraints) {
            BigInteger total = BigInteger.ZERO;
            var coefficients = constraint.coefficients();
            for (int i = 0; i < values.length; i++) {
                total = total.add(BigInteger.valueOf(coefficients[i])
                        .multiply(BigInteger.valueOf(values[i])));
            }
            assertTrue(total.compareTo(BigInteger.valueOf(constraint.minimum())) >= 0,
                    "sample=" + sample + " row=" + java.util.Arrays.toString(coefficients));
        }
    }
}
