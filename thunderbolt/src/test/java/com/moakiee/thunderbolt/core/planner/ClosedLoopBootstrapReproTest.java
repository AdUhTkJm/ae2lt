package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClosedLoopBootstrapReproTest {
    private static final ReusableStockSource SOURCE = new ReusableStockSource("tianshu", "loop");

    @Test
    void rawMembersCannotBootstrapThemselvesWhenMacroSeedIsMissing() {
        var plan = CraftPlannerV2.plan(graph(0, 0), "crystal", 640_000);

        assertFalse(plan.feasible(),
                "sulfur and uranium alone cannot start any of the three member recipes");
        assertEquals(8L, plan.missing().get("dust"));
        assertTrue(plan.usedReusableStock().isEmpty());
    }

    @Test
    void missingStartupIsRejectedRegardlessOfRecipePreferenceAndRequestSize() {
        for (boolean macroFirst : List.of(false, true)) {
            for (long amount : new long[] {1, 24, 25, 640_000}) {
                var plan = CraftPlannerV2.plan(graph(0, 0, macroFirst), "crystal", amount);
                assertFalse(plan.feasible(), "macroFirst=" + macroFirst + ", amount=" + amount);
                assertFalse(plan.missing().isEmpty());
            }
        }
    }

    @Test
    void partialSeedCannotPayForAWholeFirstRecipe() {
        for (long dust = 1; dust < 8; dust++) {
            var plan = CraftPlannerV2.plan(graph(0, dust), "crystal", 640_000);
            assertFalse(plan.feasible(), "startup dust=" + dust);
            assertEquals(8 - dust, plan.missing().get("dust"));
            assertEquals(dust, plan.usedStock().get("dust"));
        }
    }

    @Test
    void seedCanBeSplitBetweenPrivateAndNetworkInventory() {
        var plan = CraftPlannerV2.plan(graph(3, 5), "crystal", 640_000);
        assertTrue(plan.feasible(), () -> "missing: " + plan.missing());
        assertEquals(3L, plan.usedReusableStock().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(5L, plan.usedStock().get("dust"));
    }

    @Test
    void supplyingTheReportedSeedMakesTheSameRequestExecutable() {
        var unseeded = graph(0, 0);
        var missing = CraftPlannerV2.plan(unseeded, "crystal", 640_000);
        var supplied = CraftPlannerV2.plan(
                unseeded.withAdditionalStock(missing.missing()), "crystal", 640_000);
        assertTrue(supplied.feasible(), () -> "missing: " + supplied.missing());
        assertEquals(8L, supplied.usedStock().get("dust"));
    }

    @Test
    void privateSeedStillStartsTheContractedLoop() {
        var plan = CraftPlannerV2.plan(graph(8, 0), "crystal", 640_000);

        assertTrue(plan.feasible(), () -> "missing: " + plan.missing());
        assertEquals(8L, plan.usedReusableStock().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(426_672L, plan.usedStock().get("sulfur"));
        assertEquals(213_336L, plan.usedStock().get("uranium"));
        assertFalse(plan.usedStock().containsKey("dust"));
    }

    @Test
    void networkSeedIsActuallyExtracted() {
        var plan = CraftPlannerV2.plan(graph(0, 8), "crystal", 640_000);

        assertTrue(plan.feasible(), () -> "missing: " + plan.missing());
        assertEquals(8L, plan.usedStock().get("dust"));
        assertTrue(plan.usedReusableStock().isEmpty());
    }

    private static CraftGraph<String> graph(long hostDust, long networkDust) {
        return graph(hostDust, networkDust, true);
    }

    private static CraftGraph<String> graph(long hostDust, long networkDust, boolean macroFirst) {
        var macro = new CraftPattern<>("crystal", 24, List.of(
                        CraftInput.returnedFrom("dust", 8, SOURCE),
                        CraftInput.of("sulfur", 16), CraftInput.of("uranium", 8)), "macro");
        var builder = CraftGraph.<String>builder();
        if (macroFirst) builder.pattern(macro);
        builder
                .pattern("seed", 32, List.of(CraftInput.of("sulfur", 16),
                        CraftInput.of("uranium", 8), CraftInput.of("dust", 8)))
                .pattern("crystal", 1, List.of(CraftInput.of("seed", 1)))
                .pattern("dust", 1, List.of(CraftInput.of("crystal", 1)));
        if (!macroFirst) builder.pattern(macro);
        return builder
                .stock("sulfur", 426_672)
                .stock("uranium", 213_336)
                .stock("dust", networkDust)
                .reusableStock("tianshu", "dust", hostDust)
                .reusableStockRoute(SOURCE, "dust", List.of("dust"))
                .build();
    }
}
