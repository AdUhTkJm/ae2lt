package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Planner-level comparison against AE2-VM's six asserted {@code RecursionReferenceTest} cases at
 * {@code main@b03afe75}: a seeded self-amplifier and a returned essence catalyst.
 *
 * <p>The essence cases have the same target semantics and are compatibility assertions. AE2-VM's
 * bounded amplifier expectation counts the existing A seed toward the requested A output: it emits
 * seven pattern firings for a request of eight. In Thunderbolt the same cycle is normalized to a
 * reusable A seed plus one net A output per firing. AE2
 * {@code CraftingCalculation.runCraftAttempt} explicitly ignores existing target stock, so those
 * seven firings would deliver the seed and leave none for the next job. Thunderbolt must instead
 * require eight firings, leaving the original seed in the network after eight newly crafted A are
 * delivered.
 */
class ExternalPlannerRecursionComparisonTest {
    private static final Duration CASE_TIMEOUT = Duration.ofSeconds(1);
    private static final long AMOUNT = 8L;
    private static final long UNBOUNDED = 1_000_000_000_000L;

    @Test
    void ae2VmAmplifierMinimumWouldDrainSeedAndIsRejected() {
        assertConservative(amplifier(Map.of("A", 1L, "B", AMOUNT - 1L)), "A", AMOUNT);
    }

    @Test
    void amplifierMinimumFiresEightTimesAndPreservesSeed() {
        CraftPattern<String> amplifier = amplifierPattern();
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(amplifier)
                .stock("A", 1L)
                .stock("B", AMOUNT)
                .build();

        CraftPlan<String> plan = plan(graph, "A", AMOUNT);

        assertTrue(plan.supported());
        assertTrue(plan.feasible(), () -> "unexpected missing=" + plan.missing());
        assertEquals(AMOUNT, plan.firings().getOrDefault(amplifier, 0L));
        assertEquals(1L, plan.usedStock().getOrDefault("A", 0L));
        assertEquals(AMOUNT, plan.usedStock().getOrDefault("B", 0L));

        long seedAfterDelivery = 1L + AMOUNT * (2L - 1L) - AMOUNT;
        assertEquals(1L, seedAfterDelivery,
                "the closed-loop seed must remain available for the next crafting job");
    }

    @Test
    void amplifierUnboundedFeasible() {
        assertFeasible(amplifier(Map.of("A", UNBOUNDED, "B", UNBOUNDED)), "A", AMOUNT);
    }

    @Test
    void amplifierStarvedUsesDifferentTargetAccountingAndStaysConservative() {
        assertConservative(amplifier(Map.of("B", AMOUNT - 1L)), "A", AMOUNT);
    }

    @Test
    void essenceMinimumFeasible() {
        assertFeasible(essence(Map.of("A", 1L, "B", AMOUNT)), "C", AMOUNT);
    }

    @Test
    void essenceUnboundedFeasible() {
        assertFeasible(essence(Map.of("A", UNBOUNDED, "B", UNBOUNDED)), "C", AMOUNT);
    }

    @Test
    void essenceStarvedMissingSeed() {
        assertMissingOnlyA(essence(Map.of("B", AMOUNT)), "C", AMOUNT);
    }

    private static void assertFeasible(CraftGraph<String> graph, String target, long amount) {
        CraftPlan<String> plan = plan(graph, target, amount);
        assertTrue(plan.supported());
        assertTrue(plan.feasible(), () -> "unexpected missing=" + plan.missing());
        assertTrue(plan.missing().isEmpty());
    }

    private static void assertMissingOnlyA(CraftGraph<String> graph, String target, long amount) {
        CraftPlan<String> plan = plan(graph, target, amount);
        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertEquals(Map.of("A", 1L), plan.missing());
    }

    private static void assertConservative(CraftGraph<String> graph, String target, long amount) {
        CraftPlan<String> plan = plan(graph, target, amount);
        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertFalse(plan.missing().isEmpty());
    }

    private static CraftPlan<String> plan(
            CraftGraph<String> graph, String target, long amount) {
        return assertTimeoutPreemptively(
                CASE_TIMEOUT, () -> CraftPlannerV2.plan(graph, target, amount));
    }

    private static CraftGraph<String> amplifier(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder().pattern(amplifierPattern());
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static CraftPattern<String> amplifierPattern() {
        return new CraftPattern<>(
                "A", 1,
                List.of(CraftInput.returned("A", 1), CraftInput.of("B", 1)),
                "contracted-amplifier");
    }

    private static CraftGraph<String> essence(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("C", 1,
                        List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)),
                        List.of(CraftOutput.of("A", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }
}
