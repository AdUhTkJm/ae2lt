package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Reproduction of the in-world "x=169" pattern wall (overworld x=169, y=64..69, z=-32 in the 测试
 * save, exported to hmcl/exports/测试-ae2-patterns-2026-08-04.json).
 *
 * <p>The wall encodes four stacked cell-component families (appflux cores → omni → complex omni →
 * quantum omni), where every tier is crafted from the previous two tiers at 1+1 → 1. That is a
 * Fibonacci recurrence, 38 patterns deep: one quantum 256M component needs ~39M core_1k + ~63M
 * core_4k and ~102 million total pattern firings.
 */
class X169FibonacciChainReproTest {

    private static final String[] SUFFIX = {"1k", "4k", "16k", "64k", "256k", "1m", "4m", "16m", "64m", "256m"};

    private static CraftGraph<String> buildX169Graph(long baseStock) {
        CraftGraph.Builder<String> b = CraftGraph.builder();
        // Within one family: tier n = tier n-1 + tier n-2 (appflux starts at 16k; others at 16k too,
        // with their 1k/4k tiers made from the previous family's top tiers).
        String[] families = {"core", "omni", "complex", "quantum"};
        for (String fam : families) {
            for (int i = 2; i < SUFFIX.length; i++) {
                b.pattern(fam + "_" + SUFFIX[i], 1, List.of(
                        CraftInput.of(fam + "_" + SUFFIX[i - 1], 1),
                        CraftInput.of(fam + "_" + SUFFIX[i - 2], 1)));
            }
        }
        // Family bridges, exactly as encoded in the providers at (169,65..69,-32):
        b.pattern("omni_1k", 1, List.of(CraftInput.of("core_256m", 1), CraftInput.of("core_64m", 1)));
        b.pattern("omni_4k", 1, List.of(CraftInput.of("omni_1k", 1), CraftInput.of("core_256m", 1)));
        b.pattern("complex_1k", 1, List.of(CraftInput.of("omni_256m", 1), CraftInput.of("omni_64m", 1)));
        b.pattern("complex_4k", 1, List.of(CraftInput.of("complex_1k", 1), CraftInput.of("omni_256m", 1)));
        b.pattern("quantum_1k", 1, List.of(CraftInput.of("complex_256m", 1), CraftInput.of("complex_64m", 1)));
        b.pattern("quantum_4k", 1, List.of(CraftInput.of("complex_256m", 1), CraftInput.of("quantum_1k", 1)));
        b.stock("core_1k", baseStock);
        b.stock("core_4k", baseStock);
        return b.build();
    }

    @Test
    void quantum256mSingleUnitIsPlannable() {
        CraftGraph<String> g = buildX169Graph(100_000_000L);
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "quantum_256m", 1);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[x169] 1x quantum_256m: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " usedStock=" + r.plan().usedStock()
                + " totalFirings=" + r.plan().firings().values().stream().mapToLong(Long::longValue).sum()
                + " wallMs=" + ms);
        System.out.println("[x169] diagnostics=" + r.diagnostics());
        assertTrue(r.plan().feasible(), "expected feasible, got missing=" + r.plan().missing()
                + " budgetExhausted=" + r.plan().budgetExhausted());
    }

    @Test
    void quantum256mThousandUnitsIsPlannable() {
        // 1000x needs ~1e11 base items; give ample stock so the only question is planner behaviour.
        CraftGraph<String> g = buildX169Graph(200_000_000_000L);
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "quantum_256m", 1000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[x169] 1000x quantum_256m: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " budgetExhausted=" + r.plan().budgetExhausted()
                + " wallMs=" + ms);
        System.out.println("[x169] diagnostics=" + r.diagnostics());
        assertTrue(r.plan().feasible(), "expected feasible, got missing=" + r.plan().missing()
                + " budgetExhausted=" + r.plan().budgetExhausted());
    }

    /**
     * The in-game symptom: with insufficient stock the truncated search used to report missing
     * intermediates ("2 x complex component") instead of expanding to the true raw shortfall.
     * The diagnosis must reach the leaves: ~39M core_1k + ~63M core_4k.
     */
    @Test
    void quantum256mWithInsufficientStockReportsMissing() {
        CraftGraph<String> g = buildX169Graph(1_000L);
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "quantum_256m", 1);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[x169] starved quantum_256m: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " budgetExhausted=" + r.plan().budgetExhausted()
                + " wallMs=" + ms);
        System.out.println("[x169] diagnostics=" + r.diagnostics());
        assertFalse(r.plan().feasible());
        assertEquals(
                Map.of("core_1k", 39_088_169L - 1_000L, "core_4k", 63_245_986L - 1_000L),
                r.plan().missing(),
                "missing must be fully expanded to raw leaves, not truncated at intermediates");
    }

    /**
     * The second tower on the same wall (provider at 169,64,-32): ae2:cell_component upgrades at
     * 100000 -> 1 per tier. One 256k component via this route needs 1e20 1k components, which does
     * not fit in a signed 64-bit long (9.22e18).
     */
    @Test
    void overloadedCellComponentTowerOverflowsLongDemand() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("cc4k", 1, List.of(CraftInput.of("cc1k", 100_000)))
                .pattern("cc16k", 1, List.of(CraftInput.of("cc4k", 100_000)))
                .pattern("cc64k", 1, List.of(CraftInput.of("cc16k", 100_000)))
                .pattern("cc256k", 1, List.of(CraftInput.of("cc64k", 100_000)))
                .stock("cc1k", Long.MAX_VALUE / 2)
                .build();
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "cc256k", 1);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[x169-overload] 1x cc256k: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " usedStock=" + r.plan().usedStock()
                + " grossDemand=" + r.plan().grossDemand()
                + " budgetExhausted=" + r.plan().budgetExhausted()
                + " wallMs=" + ms);
    }
}
