package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class StockCoveredCyclePlanningTest {

    @Test
    void stockedUpstreamCycleDoesNotDisableASiblingRecipe() {
        for (boolean complex : List.of(false, true)) {
            for (boolean reverse : List.of(false, true)) {
                CraftPlan<String> plan = CraftPlannerV2.plan(
                        graph(complex, reverse, 57, 47, 3, 6, false), "request", 4);

                assertTrue(plan.feasible(), () -> "complex=" + complex + ", reverse=" + reverse
                        + ", missing=" + plan.missing());
                assertFalse(plan.budgetExhausted());
                assertTrue(plan.missing().isEmpty());
                assertEquals(2L, plan.usedStock().get("luminessence"));
                assertEquals(6L, plan.usedStock().get("firmament"));
                assertEquals(16L, firingsFor(plan, "supreme_circuit"));
                assertEquals(0L, firingsFor(plan, "luminessence"));
                assertEquals(0L, firingsFor(plan, "absolute_essence"));
                assertEquals(0L, firingsFor(plan, "infinity_core"));
            }
        }
    }

    @Test
    void partiallyStockedIntermediateCanStillUseItsIndependentProducer() {
        for (boolean complex : List.of(false, true)) {
            for (boolean reverse : List.of(false, true)) {
                CraftPlan<String> plan = CraftPlannerV2.plan(
                        graph(complex, reverse, 1, 0, 0, 6, true), "request", 4);

                assertTrue(plan.feasible(), () -> "missing=" + plan.missing());
                assertEquals(1L, plan.usedStock().get("luminessence"));
                assertEquals(1L, plan.usedStock().get("raw"));
                assertEquals(16L, firingsFor(plan, "supreme_circuit"));
            }
        }
    }

    @Test
    void stockedCutIsRevalidatedForLargerAmountsInTheSameSession() {
        for (boolean complex : List.of(false, true)) {
            for (List<Long> amounts : List.of(List.of(4L, 400L), List.of(400L, 4L))) {
                CraftGraph<String> graph = graph(complex, false, 57, 47, 3, 6, false);
                var session = new CraftPlannerV2.PlanningSession<String>();
                for (long amount : amounts) {
                    CraftPlan<String> plan =
                            CraftPlannerV2.planDetailed(graph, "request", amount, session).plan();

                    assertTrue(plan.feasible(), () -> "amount=" + amount + ", missing=" + plan.missing());
                    assertEquals(amount == 4 ? 2L : 57L, plan.usedStock().get("luminessence"));
                    assertEquals(amount == 4 ? 0L : 10L,
                            plan.usedStock().getOrDefault("absolute_essence", 0L));
                    assertEquals(6L, plan.usedStock().get("firmament"));
                }
            }
        }
    }

    @Test
    void alternateStockCutsRespectTheSharedSearchBudget() {
        CraftGraph<String> graph = graph(true, false, 57, 47, 3, 6, false);

        PlanningResult<String> result = CraftPlannerV2.planDetailed(
                graph, "request", 4, CraftPlannerV2.DEFAULT_VISIT_CAP, 1);

        assertFalse(result.plan().feasible());
        assertTrue(result.plan().budgetExhausted());
        assertEquals(1, result.diagnostics().planRuns());
        assertTrue(result.diagnostics().searchCutoff());
    }

    @Test
    void structuralCycleCannotCreateItsOwnInitialStock() {
        for (boolean complex : List.of(false, true)) {
            for (boolean reverse : List.of(false, true)) {
                CraftPlan<String> plan = CraftPlannerV2.plan(
                        graph(complex, reverse, 0, 0, 0, 0, false), "request", 4);

                assertFalse(plan.feasible());
                assertFalse(plan.missing().isEmpty());
            }
        }
    }

    @Test
    void insufficientStockCannotBeCountedTwiceAfterReorientation() {
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(pattern("request", 1, input("A", 1), input("B", 1)))
                .pattern(pattern("A", 1, input("B", 1)))
                .pattern(pattern("B", 1, input("A", 1)))
                .stock("A", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "request", 1);

        assertFalse(plan.feasible());
        assertEquals(1L, plan.missing().values().stream().mapToLong(Long::longValue).sum());
    }

    /**
     * Issue #53's failure shape, with the long thermal recipe chain collapsed. The requested four
     * products need two batches of firmament (six are stored), hence sixteen circuits. Producing
     * twenty thermal from two stored luminessence is sufficient for both branches. Eager structural
     * DFS nevertheless reaches circuit -> thermal through luminessence's unused producers.
     */
    private static CraftGraph<String> graph(
            boolean complex, boolean reverse, long luminessence, long essence, long cores,
            long firmament, boolean externalProducer) {
        var rootInputs = new ArrayList<>(List.of(input("thermal", 4), input("firmament", 8)));
        if (reverse) {
            Collections.reverse(rootInputs);
        }
        var builder = CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("request", 4, rootInputs, "request"))
                .pattern(pattern("thermal", 10, input("luminessence", 1)))
                .pattern(pattern("firmament", 1, input("supreme_circuit", 8)))
                .pattern(pattern("supreme_circuit", 1, input("thermal", 1)))
                .pattern(pattern("luminessence", 64, input("absolute_essence", 1)))
                .pattern(pattern("absolute_essence", 1, input("infinity_core", 1)))
                .pattern(complex
                        ? pattern("infinity_core", 4, input("firmament", 1), input("supreme_circuit", 1))
                        : pattern("infinity_core", 4, input("firmament", 1)))
                .stock("luminessence", luminessence)
                .stock("absolute_essence", essence)
                .stock("infinity_core", cores)
                .stock("firmament", firmament);
        if (externalProducer) {
            builder.pattern(pattern("luminessence", 1, input("raw", 1))).stock("raw", 1);
        }
        return builder.build();
    }

    private static long firingsFor(CraftPlan<String> plan, String output) {
        return plan.firings().entrySet().stream()
                .filter(entry -> entry.getKey().output().equals(output))
                .mapToLong(entry -> entry.getValue())
                .sum();
    }

    private static CraftInput<String> input(String key, long amount) {
        return CraftInput.of(key, amount);
    }

    @SafeVarargs
    private static CraftPattern<String> pattern(
            String output, long amount, CraftInput<String>... inputs) {
        return new CraftPattern<>(output, amount, List.of(inputs), output);
    }
}
