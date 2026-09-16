package com.moakiee.thunderbolt.core.crafting.planner.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.moakiee.thunderbolt.core.crafting.planner.CpSatRankedFlowSolver;

/** Runs the public capability suite directly through the independent CP-SAT graph model. */
class CpSatReferenceCapabilitySuiteTest {
    private static final ReferenceCapabilityRunner RUNNER = new ReferenceCapabilityRunner(
            Duration.ofSeconds(1), Duration.ofMillis(100));

    private static final ReferencePlanner CP_SAT = new ReferencePlanner() {
        @Override
        public boolean check(ReferenceScenario scenario) {
            return true;
        }

        @Override
        public com.moakiee.thunderbolt.core.crafting.planner.CraftPlan<String> plan(
                ReferenceScenario scenario) {
            var solved = CpSatRankedFlowSolver.solve(
                    scenario.graph(), scenario.target(), scenario.amount());
            return solved.status() == CpSatRankedFlowSolver.Status.SOLVED
                    ? solved.plan()
                    : null;
        }
    };

    @BeforeAll
    static void nativeRuntimeLoads() throws Exception {
        Class<?> solver = Class.forName(
                "com.moakiee.thunderbolt.core.crafting.planner.CpSatIntegerLinearSolver");
        var initialize = solver.getDeclaredMethod("initializeFromTestClasspath");
        initialize.setAccessible(true);
        assertTrue((boolean) initialize.invoke(null), "CP-SAT native runtime did not initialize");
        var available = solver.getDeclaredMethod("isAvailable");
        available.setAccessible(true);
        assertTrue((boolean) available.invoke(null), "CP-SAT native runtime did not load");
    }

    @Test
    void multiDagScenarioIsSolvedByTheIndependentCpSatModel() {
        var scenario = ThunderboltReferenceScenarios.all().stream()
                .filter(candidate -> candidate.id().equals("multi-dag/fibonacci/minimum"))
                .findFirst()
                .orElseThrow();
        var result = CpSatRankedFlowSolver.solve(
                scenario.graph(), scenario.target(), scenario.amount());

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible());
    }

    @TestFactory
    Stream<DynamicTest> referenceCapabilities() {
        return ThunderboltReferenceScenarios.all().stream().map(scenario -> DynamicTest.dynamicTest(
                scenario.id(), () -> {
                    var result = RUNNER.run(CP_SAT, scenario);
                    System.out.println("[cp-sat-reference] id=" + scenario.id()
                            + " capability=" + scenario.capability()
                            + " scale=" + scenario.scale()
                            + " status=" + result.status()
                            + " missing=" + (result.plan() == null ? null : result.plan().missing())
                            + " elapsedMs=" + result.elapsedNanos() / 1_000_000.0D);
                    assertEquals(ReferenceSupportStatus.SUPPORTED, result.status(),
                            () -> "CP-SAT capability failed: " + scenario.id()
                                    + " failure=" + result.failure());
                }));
    }
}
