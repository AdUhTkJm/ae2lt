package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class CraftPlannerV2Test {

    private static long firingsOf(CraftPlan<String> plan, CraftPattern<String> p) {
        return plan.firings().getOrDefault(p, 0L);
    }

    @Test
    void singleChainCraftsAlongOnePath() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("C", 1)))
                .stock("C", 10)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 3);

        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertEquals(3L, plan.usedStock().get("C"));
        assertTrue(plan.missing().isEmpty());
    }

    /** A byproduct of one craft satisfies a sibling demand from the shared pool, with no stock of it. */
    @Test
    void byproductFeedsSiblingDemand() {
        CraftPattern<String> t = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("main", 1), CraftInput.of("scrap", 1)), "T");
        CraftPattern<String> main = new CraftPattern<>(
                "main", 1, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("scrap", 1)), "main");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(t)
                .pattern(main)
                .stock("raw", 10)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "T", 1);

        assertTrue(plan.feasible(), "scrap should come from main's byproduct, not stock");
        assertEquals(1L, firingsOf(plan, t));
        assertEquals(1L, firingsOf(plan, main));
        assertEquals(1L, plan.usedStock().get("raw"));
        assertNull(plan.usedStock().get("scrap"));
        assertTrue(plan.missing().isEmpty());
    }

    /** Dynamic capacity selection: prefer the recipe current stock can actually fulfill. */
    @Test
    void picksRecipeFeasibleUnderStock() {
        CraftPattern<String> viaDiamond = new CraftPattern<>("A", 1, List.of(CraftInput.of("diamond", 5)), "viaDiamond");
        CraftPattern<String> viaIron = new CraftPattern<>("A", 1, List.of(CraftInput.of("iron", 5)), "viaIron");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(viaDiamond)
                .pattern(viaIron)
                .stock("diamond", 1)
                .stock("iron", 100)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 1);

        assertTrue(plan.feasible());
        assertEquals(0L, firingsOf(plan, viaDiamond));
        assertEquals(1L, firingsOf(plan, viaIron));
        assertEquals(5L, plan.usedStock().get("iron"));
    }

    /**
     * Capacity is optimistic (it double-counts a shared input), so the highest-capacity recipe is
     * tried first and fails at runtime; bounded backtracking rolls it back and the alternative wins.
     */
    @Test
    void boundedBacktrackRecoversAlternative() {
        // r1 needs B+D, both made from a shared pool of 4 -> 3 A would need 6 shared (infeasible).
        CraftPattern<String> r1 = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 1), CraftInput.of("D", 1)), "r1");
        CraftPattern<String> b = new CraftPattern<>("B", 1, List.of(CraftInput.of("shared", 1)), "B");
        CraftPattern<String> d = new CraftPattern<>("D", 1, List.of(CraftInput.of("shared", 1)), "D");
        // r2 needs iron, exactly enough for 3 A.
        CraftPattern<String> r2 = new CraftPattern<>("A", 1, List.of(CraftInput.of("iron", 1)), "r2");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(r1).pattern(r2).pattern(b).pattern(d)
                .stock("shared", 4)
                .stock("iron", 3)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 3);

        // The allocation search may now legitimately SPLIT demand across A's routes (e.g. 2 via the
        // shared recipe + 1 via iron) instead of rolling the shared branch back wholesale; assert
        // feasibility and mass balance rather than one specific route assignment.
        assertTrue(plan.feasible(), "should recover via the iron recipe (fully or by splitting)");
        long viaShared = firingsOf(plan, r1);
        long viaIron = firingsOf(plan, r2);
        assertEquals(3L, viaShared + viaIron);
        assertEquals(2L * viaShared, plan.usedStock().getOrDefault("shared", 0L));
        assertEquals(viaIron, (long) plan.usedStock().getOrDefault("iron", 0L));
        assertTrue(plan.usedStock().getOrDefault("shared", 0L) <= 4);
        assertTrue(plan.missing().isEmpty());
    }

    /** Returned (container/in-pattern catalyst) input needs one seed, not amount*times. */
    @Test
    void returnedInputCostsOneSeedNotPerCraft() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("filled", 1, List.of(CraftInput.of("water", 1), CraftInput.returned("bucket", 1)))
                .stock("water", 1000)
                .stock("bucket", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "filled", 100);

        assertTrue(plan.feasible(), "1 bucket should be reused 100 times");
        assertEquals(100L, plan.usedStock().get("water"));
        assertEquals(1L, plan.usedStock().get("bucket"));
    }

    /** The same unchanged catalyst is a global reusable reserve, not one seed per distinct pattern. */
    @Test
    void unchangedCatalystIsSharedAcrossLinearPatterns() {
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("target", 1, List.of(
                        CraftInput.of("intermediate", 1),
                        CraftInput.returned("template", 1)))
                .pattern("intermediate", 1, List.of(
                        CraftInput.of("raw", 1),
                        CraftInput.returned("template", 1)))
                .stock("raw", 1)
                .stock("template", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "target", 1);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("raw"));
        assertEquals(1L, plan.usedStock().get("template"));
    }

    /**
     * A sibling byproduct must not algebraically bootstrap an earlier catalyst when that sibling
     * itself depends on the catalyzed output.
     */
    @Test
    void indirectByproductCannotBootstrapReturnedCatalyst() {
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("target", 1, List.of(CraftInput.of("catalyzed", 1)),
                        List.of(CraftOutput.of("seed", 1)))
                .pattern("catalyzed", 1, List.of(CraftInput.returned("seed", 1)))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "target", 1);

        assertFalse(plan.feasible());
        assertEquals(1L, plan.missing().get("seed"));
    }

    @Test
    void returnedSeedShortfallIsReportedOnceForTheWholeCalculation() {
        CraftGraph<String> noSeed = CraftGraph.<String>builder()
                .pattern("product", 1, List.of(
                        CraftInput.of("material", 4), CraftInput.returned("seed", 2)))
                .stock("material", 400)
                .build();
        CraftGraph<String> oneOfTwoSeeds = CraftGraph.<String>builder()
                .pattern("product", 1, List.of(
                        CraftInput.of("material", 4), CraftInput.returned("seed", 2)))
                .stock("material", 400)
                .stock("seed", 1)
                .build();

        CraftPlan<String> missingAll = CraftPlannerV2.plan(noSeed, "product", 100);
        CraftPlan<String> missingOne = CraftPlannerV2.plan(oneOfTwoSeeds, "product", 100);

        assertFalse(missingAll.feasible());
        assertEquals(2L, missingAll.missing().get("seed"));
        assertFalse(missingOne.feasible());
        assertEquals(1L, missingOne.usedStock().get("seed"));
        assertEquals(1L, missingOne.missing().get("seed"));
        assertEquals(400L, missingOne.grossDemand().get("material"));
    }

    @Test
    void hostReusableStockCannotLeakIntoOrdinarySiblingDemand() {
        var source = new ReusableStockSource("host", "shared-loop-pool");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(
                        CraftInput.of("loop-output", 1), CraftInput.of("ordinary-output", 1)))
                .pattern("loop-output", 1, List.of(CraftInput.returnedFrom("seed", 1, source)))
                .pattern("ordinary-output", 1, List.of(CraftInput.of("seed", 1)))
                .reusableStock("host", "seed", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertFalse(plan.feasible(), "private seed must not satisfy an ordinary recipe");
        assertEquals(1L, plan.missing().get("seed"));
        assertEquals(1L, plan.usedReusableStock().get(
                new ReusableStockUsageKey<>("host", "shared-loop-pool", "seed")));
        assertNull(plan.usedStock().get("seed"));
    }

    @Test
    void sharedLoopPoolReusesOnePhysicalHostSeedAcrossPatterns() {
        var source = new ReusableStockSource("host", "shared-loop-pool");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(CraftInput.of("left", 1), CraftInput.of("right", 1)))
                .pattern("left", 1, List.of(CraftInput.returnedFrom("seed", 1, source)))
                .pattern("right", 1, List.of(CraftInput.returnedFrom("seed", 1, source)))
                .reusableStock("host", "seed", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedReusableStock().get(
                new ReusableStockUsageKey<>("host", "shared-loop-pool", "seed")));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void nonExactReturnedSeedStaysPrivateToItsRoutingConsumer() {
        var first = new ReusableStockSource("host", "shared-loop-pool", "first-route");
        var second = new ReusableStockSource("host", "shared-loop-pool", "second-route");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(
                        CraftInput.of("first", 1), CraftInput.of("second", 1)))
                .pattern("first", 1, List.of(CraftInput.returnedFrom("A", 1, first)))
                .pattern("second", 1, List.of(CraftInput.returnedFrom("A", 1, second)))
                .reusableStock("host", "X", 1)
                .reusableStockRoute(first, "A", List.of("A", "X"))
                .reusableStockRoute(second, "A", List.of("A", "X"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertFalse(plan.feasible(), "one fuzzy X must not become a cross-consumer logical A");
        assertEquals(1L, plan.usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
        assertEquals(1L, plan.missing().get("A"));
    }

    @Test
    void nonExactReturnedSeedCanBeReusedInsideTheSameRoutingConsumer() {
        var source = new ReusableStockSource("host", "shared-loop-pool", "one-route");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(
                        CraftInput.of("first", 1), CraftInput.of("second", 1)))
                .pattern("first", 1, List.of(CraftInput.returnedFrom("A", 1, source)))
                .pattern("second", 1, List.of(CraftInput.returnedFrom("A", 1, source)))
                .reusableStock("host", "X", 1)
                .reusableStockRoute(source, "A", List.of("A", "X"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedReusableStock().get(
                new ReusableStockUsageKey<>(
                        "host", "shared-loop-pool", "one-route", "A", "X")));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void exactReturnedSeedRemainsShareableAcrossRoutingConsumers() {
        var first = new ReusableStockSource("host", "shared-loop-pool", "first-route");
        var second = new ReusableStockSource("host", "shared-loop-pool", "second-route");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(
                        CraftInput.of("first", 1), CraftInput.of("second", 1)))
                .pattern("first", 1, List.of(CraftInput.returnedFrom("A", 1, first)))
                .pattern("second", 1, List.of(CraftInput.returnedFrom("A", 1, second)))
                .reusableStock("host", "A", 1)
                .reusableStockRoute(first, "A", List.of("A"))
                .reusableStockRoute(second, "A", List.of("A"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedReusableStock().get(
                new ReusableStockUsageKey<>(
                        "host", "shared-loop-pool", "first-route", "A", "A")));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void sharedExactAllocationCannotBeRematchedAfterAnotherRouteConsumesItsCredit() {
        var first = new ReusableStockSource("host", "shared-loop-pool", "first-route");
        var second = new ReusableStockSource("host", "shared-loop-pool", "second-route");
        var constrained = new ReusableStockSource(
                "host", "other-shared-loop-pool", "constrained-route");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(
                        CraftInput.of("first", 1),
                        CraftInput.of("second", 1),
                        CraftInput.of("constrained", 1)))
                .pattern("first", 1, List.of(CraftInput.returnedFrom("A", 1, first)))
                .pattern("second", 1, List.of(CraftInput.returnedFrom("A", 1, second)))
                .pattern("constrained", 1,
                        List.of(CraftInput.returnedFrom("B", 1, constrained)))
                .reusableStock("host", "A", 1)
                .reusableStock("host", "X", 1)
                .reusableStockRoute(first, "A", List.of("A", "X"))
                .reusableStockRoute(second, "A", List.of("A", "X"))
                .reusableStockRoute(constrained, "B", List.of("A"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertFalse(plan.feasible(),
                "the later B route must not steal exact A after A entered the shared pool");
        assertEquals(1L, plan.missing().get("B"));
        assertEquals(1L, plan.usedReusableStock().get(
                new ReusableStockUsageKey<>(
                        "host", "shared-loop-pool", "first-route", "A", "A")));
    }

    @Test
    void dedicatedLoopPoolsCompeteForTheSamePhysicalHostSeed() {
        var leftPool = new ReusableStockSource("host", "left-loop");
        var rightPool = new ReusableStockSource("host", "right-loop");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(CraftInput.of("left", 1), CraftInput.of("right", 1)))
                .pattern("left", 1, List.of(CraftInput.returnedFrom("seed", 1, leftPool)))
                .pattern("right", 1, List.of(CraftInput.returnedFrom("seed", 1, rightPool)))
                .reusableStock("host", "seed", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertFalse(plan.feasible(), "dedicated pools must not borrow each other's phase state");
        assertEquals(1L, plan.missing().get("seed"));
        assertEquals(1L, plan.usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
    }

    @Test
    void dedicatedHostBorrowRetainsItsLogicalPoolIdentity() {
        var leftPool = new ReusableStockSource("host", "left-loop");
        var rightPool = new ReusableStockSource("host", "right-loop");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("result", 1, List.of(CraftInput.of("left", 1), CraftInput.of("right", 1)))
                .pattern("left", 1, List.of(CraftInput.returnedFrom("seed", 1, leftPool)))
                .pattern("right", 1, List.of(CraftInput.returnedFrom("seed", 1, rightPool)))
                .reusableStock("host", "seed", 2)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedReusableStock().get(
                new ReusableStockUsageKey<>("host", "left-loop", "seed")));
        assertEquals(1L, plan.usedReusableStock().get(
                new ReusableStockUsageKey<>("host", "right-loop", "seed")));
    }

    @Test
    void fuzzyReusableStockDoesNotPreallocateSharedVariantToUnrequestedPattern() {
        var sourceA = new ReusableStockSource("host", "shared", "route-a");
        var sourceB = new ReusableStockSource("host", "shared", "route-b");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("A-product", 1, List.of(CraftInput.returnedFrom("A", 1, sourceA)))
                .pattern("B-product", 1, List.of(CraftInput.returnedFrom("B", 1, sourceB)))
                .reusableStock("host", "X", 1)
                .reusableStockRoute(sourceA, "A", List.of("X"))
                .reusableStockRoute(sourceB, "B", List.of("X"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B-product", 1);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedReusableStock().get(
                new ReusableStockUsageKey<>("host", "shared", "route-b", "B", "X")));
    }

    @Test
    void overlappingFuzzyReusableRoutesCannotDoubleSpendOneActualVariant() {
        var sourceA = new ReusableStockSource("host", "shared", "route-a");
        var sourceB = new ReusableStockSource("host", "shared", "route-b");
        CraftGraph<String> graph = fuzzySharedVariantGraph(sourceA, sourceB, 1, 0, false);

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertFalse(plan.feasible());
        assertEquals(1L, plan.usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
    }

    @Test
    void fuzzyReusableMatchingReassignsEarlierBorrowAndIgnoresPatternOrder() {
        var sourceA = new ReusableStockSource("host", "shared", "route-a");
        var sourceB = new ReusableStockSource("host", "shared", "route-b");

        CraftPlan<String> forward = CraftPlannerV2.plan(
                fuzzySharedVariantGraph(sourceA, sourceB, 1, 1, false), "result", 1);
        CraftPlan<String> reversed = CraftPlannerV2.plan(
                fuzzySharedVariantGraph(sourceA, sourceB, 1, 1, true), "result", 1);

        for (var plan : List.of(forward, reversed)) {
            assertTrue(plan.feasible());
            assertEquals(1L, plan.usedReusableStock().get(
                    new ReusableStockUsageKey<>("host", "shared", "route-a", "A", "Y")));
            assertEquals(1L, plan.usedReusableStock().get(
                    new ReusableStockUsageKey<>("host", "shared", "route-b", "B", "X")));
        }
    }

    private static CraftGraph<String> fuzzySharedVariantGraph(
            ReusableStockSource sourceA,
            ReusableStockSource sourceB,
            long sharedX,
            long onlyA,
            boolean reverse) {
        var builder = CraftGraph.<String>builder();
        if (reverse) {
            builder.pattern("result", 1, List.of(
                    CraftInput.of("B-product", 1), CraftInput.of("A-product", 1)))
                    .pattern("B-product", 1, List.of(CraftInput.returnedFrom("B", 1, sourceB)))
                    .pattern("A-product", 1, List.of(CraftInput.returnedFrom("A", 1, sourceA)));
        } else {
            builder.pattern("result", 1, List.of(
                    CraftInput.of("A-product", 1), CraftInput.of("B-product", 1)))
                    .pattern("A-product", 1, List.of(CraftInput.returnedFrom("A", 1, sourceA)))
                    .pattern("B-product", 1, List.of(CraftInput.returnedFrom("B", 1, sourceB)));
        }
        return builder
                .reusableStock("host", "X", sharedX)
                .reusableStock("host", "Y", onlyA)
                .reusableStockRoute(sourceA, "A", List.of("X", "Y"))
                .reusableStockRoute(sourceB, "B", List.of("X"))
                .build();
    }

    /**
     * Durability tool {@code 1·tool(4) + 1·B → 1·C + tool(3)}: the degradation chain is solved once
     * and reduced to the closed form {@code tools = ceil(times / uses)} — 100 crafts need ceil(100/4)
     * = 25 tools, not 100 (普通) nor 1 (无限催化剂). This is the "成环差分" reduction.
     */
    @Test
    void finiteUseToolBatchesByUses() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("C", 1, List.of(CraftInput.of("B", 1), CraftInput.finiteUse("tool", 1, 4)))
                .stock("B", 1000)
                .stock("tool", 25)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", 100);

        assertTrue(plan.feasible());
        assertEquals(100L, plan.usedStock().get("B"));
        assertEquals(25L, plan.usedStock().get("tool"), "ceil(100/4) tools, each surviving 4 firings");
        assertTrue(plan.missing().isEmpty());
    }

    /** One tool short of capacity: the chain cannot cover the last batch, surfacing the shortfall. */
    @Test
    void finiteUseToolShortfallIsReported() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("C", 1, List.of(CraftInput.of("B", 1), CraftInput.finiteUse("tool", 1, 4)))
                .stock("B", 1000)
                .stock("tool", 24) // covers only 96 firings
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", 100);

        assertFalse(plan.feasible());
        assertEquals(1L, plan.missing().get("tool"), "ceil(100/4)=25 needed, 24 on hand");
    }

    /** Closed form stays O(1) at scale: a million crafts resolve without per-firing iteration. */
    @Test
    void finiteUseToolScalesWithoutEnumeration() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("C", 1, List.of(CraftInput.of("B", 1), CraftInput.finiteUse("tool", 1, 1000)))
                .stock("B", 5_000_000)
                .stock("tool", 1000)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", 1_000_000);

        assertTrue(plan.feasible());
        assertEquals(1_000_000L, plan.usedStock().get("B"));
        assertEquals(1000L, plan.usedStock().get("tool"));
    }

    /**
     * Craftable durability tool, modeled as the adapter does: the tool is a "use" currency, one craft
     * yields {@code n} uses (output amount n). Existing (partial) uses in stock are spent first, then
     * {@code ceil(shortfall / n)} fresh tools are crafted — "按链长×数量" preferring stock before craft.
     */
    @Test
    void craftableToolYieldsNUsesPerCraftStockFirst() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("C", 1, List.of(CraftInput.of("B", 1), CraftInput.of("use", 1)))
                .pattern("use", 4, List.of(CraftInput.of("ingot", 3))) // 1 craft = 1 full tool = 4 uses
                .stock("B", 1000)
                .stock("use", 2)   // a half-spent tool already on hand: 2 uses
                .stock("ingot", 100)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", 10);

        assertTrue(plan.feasible());
        assertEquals(10L, plan.usedStock().get("B"));
        assertEquals(2L, plan.usedStock().get("use"), "spend the 2 stocked uses before crafting");
        assertEquals(6L, plan.usedStock().get("ingot"), "ceil((10-2)/4)=2 tools crafted -> 6 ingot");
        assertTrue(plan.missing().isEmpty());
    }

    /**
     * End-to-end durability: the chain is <b>built step by step</b> via {@link DurabilityChain} (exactly
     * as the adapter walks getRemainingKey — n is derived, not given), then fed to the planner as the
     * adapter does (carrier = full tool, stock = aggregate uses, craft output scaled by n). Three
     * random-durability tools sit in stock; requests run up to 1e18 with max durability up to 8192.
     * Verifies the closed form crafts exactly the right number of fresh tools, that the leftover
     * durability handed back is a single valid partial tool, and that the degraded-first translation
     * draws concrete tools covering exactly what was spent from stock.
     */
    @Test
    void craftableToolWithRandomStockAndHugeAmountsBalancesDurability() {
        Random rnd = new Random(20260630L);
        for (int iter = 0; iter < 2000; iter++) {
            long maxDur = 1 + rnd.nextInt(8192);                 // D ∈ [1, 8192]
            // 3 tools with random remaining durability r ∈ [1, D]; a tool with r left sits at link D-r.
            long[] rem = {
                1 + (long) (rnd.nextDouble() * maxDur),
                1 + (long) (rnd.nextDouble() * maxDur),
                1 + (long) (rnd.nextDouble() * maxDur),
            };
            Map<String, Long> toolStock = new HashMap<>();
            for (long r : rem) {
                toolStock.merge("t" + (maxDur - r), 1L, Long::sum);
            }
            Function<String, String> degrade = k -> {
                long dd = Long.parseLong(k.substring(1));
                return dd + 1 < maxDur ? "t" + (dd + 1) : null;
            };

            DurabilityChain<String> chain =
                    DurabilityChain.build("t0", degrade, k -> toolStock.getOrDefault(k, 0L), 8192);
            // d==1 tools have no chain (single use); skip those degenerate draws for this end-to-end case.
            if (chain == null) {
                continue;
            }
            assertEquals(maxDur, chain.n(), "n derived from chain length");
            String use = chain.carrier();                        // = "t0", the full tool key
            long stockUses = chain.totalUses();
            assertEquals(rem[0] + rem[1] + rem[2], stockUses, "Σ链长×数量");

            long want = rnd.nextBoolean()
                    ? 1 + rnd.nextInt(500)                       // small: may stay within stock
                    : 1 + (long) (rnd.nextDouble() * 1e18);      // huge: forces crafting, ≤ 1e18

            CraftPattern<String> useCraft = new CraftPattern<>(
                    use, maxDur, List.of(CraftInput.of("ingot", 1)), "useCraft"); // 1 craft = 1 full tool = D uses
            CraftPattern<String> cCraft = new CraftPattern<>(
                    "C", 1, List.of(CraftInput.of("B", 1), CraftInput.of(use, 1)), "cCraft");
            CraftGraph<String> g = CraftGraph.<String>builder()
                    .pattern(cCraft)
                    .pattern(useCraft)
                    .stock(use, stockUses)   // carrier stock = aggregate uses (what the adapter sets)
                    .stock("B", want)        // enough raw for every C
                    .stock("ingot", want)    // enough raw for every fresh tool (tools ≤ want)
                    .build();

            CraftPlan<String> plan = CraftPlannerV2.plan(g, "C", want);

            long shortfall = Math.max(0, want - stockUses);
            long tools = shortfall == 0 ? 0 : (shortfall + maxDur - 1) / maxDur; // ceil(shortfall / D)
            long fromStock = Math.min(want, stockUses);
            long leftover = stockUses + tools * maxDur - want;   // durability returned to the network

            String msg = "iter=" + iter + " D=" + maxDur + " stock=" + stockUses + " want=" + want;
            assertTrue(plan.feasible(), msg);
            assertTrue(plan.missing().isEmpty(), msg);
            assertEquals(want, plan.usedStock().get("B"), msg);
            assertEquals(fromStock, plan.usedStock().getOrDefault(use, 0L), msg);
            assertEquals(tools, firingsOf(plan, useCraft), msg);
            assertEquals(tools, plan.usedStock().getOrDefault("ingot", 0L), msg);
            assertTrue(leftover >= 0, msg + " leftover=" + leftover);
            if (tools > 0) {
                assertTrue(leftover < maxDur, msg + " leftover=" + leftover); // one partial tool, not a whole wasted one
            }

            // Degraded-first translation of the uses actually spent from stock draws real tools that
            // cover exactly that demand (within one partial tool) — what the adapter writes to usedItems.
            Map<String, Long> drawn = new HashMap<>();
            chain.chargeFromStock(fromStock, (k, c) -> drawn.merge(k, c, Long::sum));
            long covered = 0;
            for (Map.Entry<String, Long> e : drawn.entrySet()) {
                long idx = Long.parseLong(e.getKey().substring(1));
                covered += (maxDur - idx) * e.getValue();
                assertTrue(e.getValue() <= toolStock.get(e.getKey()), msg + " over-draw @" + e.getKey());
            }
            assertTrue(covered >= fromStock && covered - fromStock < maxDur, msg + " covered=" + covered);
        }
    }

    @Test
    void reportsMissingWhenNoRecipeCanBeFulfilled() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("diamond", 5)))
                .stock("diamond", 2)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 1);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertEquals(3L, plan.missing().get("diamond"));
    }

    /** The diamond cascade must be visited near-linearly, never the exponential 2^depth. */
    @Test
    void diamondCascadeIsLinearNotExponential() {
        int diamonds = 20;
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        String prevConv = "I0";
        for (int i = 1; i <= diamonds; i++) {
            String left = "L" + i;
            String right = "R" + i;
            String conv = "I" + i;
            b.pattern(prevConv, 1, List.of(CraftInput.of(left, 1)));
            b.pattern(prevConv, 1, List.of(CraftInput.of(right, 1)));
            b.pattern(left, 1, List.of(CraftInput.of(conv, 1)));
            b.pattern(right, 1, List.of(CraftInput.of(conv, 1)));
            prevConv = conv;
        }
        b.stock(prevConv, 1_000);

        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "I0", 1);

        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertTrue(plan.itemsProcessed() <= 1 + 3 * diamonds,
                "expected ~O(k) processing, got " + plan.itemsProcessed());
    }

    @Test
    void seedOrderedBoundaryDoesNotPoisonOrdinaryDiamondDescendants() {
        int depth = 26;
        ReusableStockSource source = new ReusableStockSource("host", "pool");
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (int i = 0; i < depth; i++) {
            List<CraftInput<String>> leftInputs = new ArrayList<>();
            leftInputs.add(CraftInput.of("A" + (i + 1), 1));
            leftInputs.add(CraftInput.of("B" + (i + 1), 1));
            if (i == 0) {
                leftInputs.add(CraftInput.returnedFrom("tool", 1, source));
            }
            builder.pattern("A" + i, 1, leftInputs);
            builder.pattern("B" + i, 1, List.of(
                    CraftInput.of("A" + (i + 1), 1),
                    CraftInput.of("B" + (i + 1), 1)));
        }
        long leafStock = 1L << depth;
        builder.stock("A" + depth, leafStock)
                .stock("B" + depth, leafStock)
                .reusableStock("host", "tool", 1)
                .reusableStockRoute(source, "tool", List.of("tool"));

        PlanningResult<String> planning = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> CraftPlannerV2.planDetailed(builder.build(), "A0", 1, 1, 1));

        assertTrue(planning.plan().feasible());
        assertFalse(planning.plan().budgetExhausted());
        assertTrue(planning.plan().missing().isEmpty());
        assertFalse(planning.diagnostics().searchCutoff());
        assertEquals(0, planning.diagnostics().consumedSearchBudget(),
                "one deterministic seed boundary is resolution work, not route search");
        assertEquals(1, planning.diagnostics().consumedResolutionBudget());
        assertFalse(planning.diagnostics().fallbackCutoff());
        assertEquals(0, planning.diagnostics().consumedFallbackBudget());
        assertTrue(planning.plan().itemsProcessed() <= 4 * depth,
                "ordinary descendants should be aggregated per boundary, got "
                        + planning.plan().itemsProcessed());
    }

    @Test
    void resolutionBudgetBoundsAllSeedOrderedDiamondWithoutChargingSearch() {
        int depth = 18;
        ReusableStockSource source = new ReusableStockSource("host", "pool");
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (int i = 0; i < depth; i++) {
            List<CraftInput<String>> inputs = List.of(
                    CraftInput.of("A" + (i + 1), 1),
                    CraftInput.of("B" + (i + 1), 1),
                    CraftInput.returnedFrom("tool", 1, source));
            builder.pattern("A" + i, 1, inputs);
            builder.pattern("B" + i, 1, inputs);
        }
        builder.stock("A" + depth, 1)
                .stock("B" + depth, 1)
                .reusableStock("host", "tool", 1)
                .reusableStockRoute(source, "tool", List.of("tool"));

        PlanningResult<String> planning = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> CraftPlannerV2.planDetailed(builder.build(), "A0", 1, 1, 1));

        assertFalse(planning.plan().feasible());
        assertTrue(planning.plan().budgetExhausted());
        assertEquals(0, planning.diagnostics().consumedSearchBudget());
        assertFalse(planning.diagnostics().searchCutoff());
        assertTrue(planning.diagnostics().resolutionCutoff());
        assertEquals(
                planning.diagnostics().configuredResolutionBudget(),
                planning.diagnostics().consumedResolutionBudget());
    }

    @Test
    void overflowSaturatesToMissingNotWraparound() {
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        int depth = 25;
        for (int i = 0; i < depth; i++) {
            b.pattern("X" + i, 1, List.of(CraftInput.of("X" + (i + 1), 10)));
        }

        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "X0", 1);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertTrue(plan.missing().getOrDefault("X" + depth, 0L) > 0,
                "saturated demand must surface as positive missing amount");
    }

    @Test
    void pureCycleIsBrokenTowardTargetNotDeclined() {
        // A -> B, B -> A : the back-edge from B to A (the target, being made) is cut, so B becomes a
        // leaf. With no stock the plan is supported but infeasible (missing B), never an infinite loop.
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 1);
        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertEquals(1L, plan.missing().get("B"));
    }

    @Test
    void compressCycleMakesBlocksFromIngotStock() {
        // 9 ingot -> 1 block (compress) and 1 block -> 9 ingot (decompress). Target = block, stock =
        // ingots: the decompress recipe is the back-edge and gets cut, so blocks are made from ingots.
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("block", 1, List.of(CraftInput.of("ingot", 9)))
                .pattern("ingot", 9, List.of(CraftInput.of("block", 1)))
                .stock("ingot", 64)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "block", 5);
        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertEquals(45L, plan.usedStock().get("ingot")); // 5 blocks * 9 ingots
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void conversionSccUsesResidualStockRegardlessOfParentInputOrderAtIntMaxScale() {
        long scale = Integer.MAX_VALUE;
        CraftPattern<String> stoneFromCobble = new CraftPattern<>(
                "stone", 1, List.of(CraftInput.of("cobble", 1)), "stoneFromCobble");
        CraftPattern<String> cobbleFromStone = new CraftPattern<>(
                "cobble", 1, List.of(CraftInput.of("stone", 1)), "cobbleFromStone");
        CraftPattern<String> ironFromStone = new CraftPattern<>(
                "iron", 1, List.of(CraftInput.of("stone", 1)), "ironFromStone");

        CraftPlan<String> stoneFirst = CraftPlannerV2.plan(
                conversionOrderGraph(List.of(
                        CraftInput.of("iron", 1),
                        CraftInput.of("stone", 547),
                        CraftInput.of("cobble", 653)),
                        scale, stoneFromCobble, cobbleFromStone, ironFromStone),
                "target", scale);
        CraftPlan<String> cobbleFirst = CraftPlannerV2.plan(
                conversionOrderGraph(List.of(
                        CraftInput.of("cobble", 653),
                        CraftInput.of("iron", 1),
                        CraftInput.of("stone", 547)),
                        scale, stoneFromCobble, cobbleFromStone, ironFromStone),
                "target", scale);

        for (CraftPlan<String> plan : List.of(stoneFirst, cobbleFirst)) {
            assertTrue(plan.feasible(), "parent input order must not choose the wrong SCC cut");
            assertEquals(649L * scale, plan.usedStock().get("stone"));
            assertEquals(552L * scale, plan.usedStock().get("cobble"));
            assertEquals(101L * scale, firingsOf(plan, cobbleFromStone));
            assertEquals(0L, firingsOf(plan, stoneFromCobble));
        }
    }

    @Test
    void catalyzedConversionSccCanAlsoReorientAtIntMaxScale() {
        long scale = Integer.MAX_VALUE;
        CraftPattern<String> stoneFromCobble = new CraftPattern<>(
                "stone", 1, List.of(CraftInput.of("cobble", 1)), "stoneFromCobble");
        CraftPattern<String> cobbleFromStone = new CraftPattern<>(
                "cobble", 1, List.of(
                        CraftInput.of("stone", 1), CraftInput.of("tool", 1)), "cobbleFromStone");
        CraftPattern<String> ironFromStone = new CraftPattern<>(
                "iron", 1, List.of(CraftInput.of("stone", 1)), "ironFromStone");
        CraftGraph.Builder<String> builder = CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("target", 1, List.of(
                        CraftInput.of("iron", 1),
                        CraftInput.of("stone", 547),
                        CraftInput.of("cobble", 653)), "target"))
                .pattern(stoneFromCobble)
                .pattern(cobbleFromStone)
                .pattern(ironFromStone)
                .stock("stone", 1000L * scale)
                .stock("cobble", 552L * scale)
                .stock("tool", 101L * scale);

        CraftPlan<String> plan = CraftPlannerV2.plan(builder.build(), "target", scale);

        assertTrue(plan.feasible());
        assertEquals(101L * scale, firingsOf(plan, cobbleFromStone));
        assertEquals(101L * scale, plan.usedStock().get("tool"));
    }

    private static CraftGraph<String> conversionOrderGraph(
            List<CraftInput<String>> targetInputs,
            long scale,
            CraftPattern<String> stoneFromCobble,
            CraftPattern<String> cobbleFromStone,
            CraftPattern<String> ironFromStone) {
        return CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("target", 1, targetInputs, "target"))
                .pattern(stoneFromCobble)
                .pattern(cobbleFromStone)
                .pattern(ironFromStone)
                .stock("stone", 1000L * scale)
                .stock("cobble", 552L * scale)
                .build();
    }

    @Test
    void containerIsConsumedAndRefilledFromItsOwnLeftover() {
        // Container model the adapter builds for a filled bucket: making P consumes one full bucket and
        // hands back an empty one (byproduct); the empty + water refills a full bucket. With a single
        // seed bucket + water, a batch of P should reuse the returned empties instead of needing N fulls.
        CraftPattern<String> makeP = new CraftPattern<>(
                "P", 1, List.of(CraftInput.of("full_bucket", 1)),
                List.of(CraftOutput.of("empty_bucket", 1)), "makeP");
        CraftPattern<String> refill = new CraftPattern<>(
                "full_bucket", 1, List.of(CraftInput.of("empty_bucket", 1), CraftInput.of("water", 1)), "refill");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(makeP)
                .pattern(refill)
                .stock("full_bucket", 1)
                .stock("water", 1000)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "P", 5);

        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("full_bucket")); // one seed bucket, reused
        assertEquals(4L, plan.usedStock().get("water"));       // 4 refills from the 5 returned empties
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void partialFeedbackLoopKeepsOneRecoveredBatchAsBootstrapSeed() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 2)),
                List.of(CraftOutput.of("C", 1)), "makeB");
        CraftPattern<String> recoverA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("C", 1)), "recoverA");
        CraftGraph<String> shortOneSeed = CraftGraph.<String>builder()
                .pattern(makeB).pattern(recoverA).stock("A", 2).build();
        CraftGraph<String> seeded = CraftGraph.<String>builder()
                .pattern(makeB).pattern(recoverA).stock("A", 3).build();

        CraftPlan<String> shortPlan = CraftPlannerV2.plan(shortOneSeed, "B", 4);
        CraftPlan<String> seededPlan = CraftPlannerV2.plan(seeded, "B", 4);

        assertFalse(shortPlan.feasible(), "net 2 A is not enough to start two 2-A firings");
        // C is one tier deeper than A, so the graded objective asks for one recoverable C seed.
        assertEquals(Map.of("C", 1L), shortPlan.missing());
        assertTrue(seededPlan.feasible());
        assertEquals(3L, seededPlan.usedStock().get("A"));
        assertEquals(2L, firingsOf(seededPlan, makeB));
        assertEquals(1L, firingsOf(seededPlan, recoverA));
    }

    @Test
    void partialFeedbackLoopScalesAtIntMaxWithoutPerFiringSimulation() {
        long scale = Integer.MAX_VALUE;
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 2)),
                List.of(CraftOutput.of("C", 1)), "makeB");
        CraftPattern<String> recoverA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("C", 1)), "recoverA");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(recoverA).stock("A", scale + 1).build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 2L * scale);

        assertTrue(plan.feasible());
        assertEquals(scale + 1, plan.usedStock().get("A"));
        assertEquals(scale, firingsOf(plan, makeB));
        assertEquals(scale - 1, firingsOf(plan, recoverA));
    }

    @Test
    void lossyRawFeedbackChargesNetLossAndBootstrapReserve() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 3)), "3A_to_2B");
        CraftPattern<String> makeD = new CraftPattern<>(
                "D", 1, List.of(CraftInput.of("B", 2)),
                List.of(CraftOutput.of("A", 2)), "2B_to_D_2A");
        CraftGraph<String> shortReserve = CraftGraph.<String>builder()
                .pattern(makeB).pattern(makeD).stock("A", 8).build();
        CraftGraph<String> complete = CraftGraph.<String>builder()
                .pattern(makeB).pattern(makeD).stock("A", 10).build();

        CraftPlan<String> shortPlan = CraftPlannerV2.plan(shortReserve, "D", 8);
        CraftPlan<String> completePlan = CraftPlannerV2.plan(complete, "D", 8);

        assertFalse(shortPlan.feasible(), "8 net A cannot provide the retained 2-A state");
        assertEquals(8L, shortPlan.usedStock().get("A"));
        assertEquals(Map.of("A", 2L), shortPlan.missing());
        assertTrue(completePlan.feasible());
        assertEquals(10L, completePlan.usedStock().get("A"));
        assertEquals(8L, firingsOf(completePlan, makeB));
        assertEquals(8L, firingsOf(completePlan, makeD));
        assertTrue(completePlan.missing().isEmpty());
    }

    @Test
    void weightedPositiveFeedbackUsesExactRequestResidueSeed() {
        for (long amount : List.of(1L, 2L, 3L, 8L, 1_000L)) {
            long minimumSeed = weightedPositiveMinimumSeed(amount);
            WeightedPositiveFeedback feedback = weightedPositiveFeedback(minimumSeed);

            CraftPlan<String> plan = CraftPlannerV2.plan(feedback.graph(), "T", amount);

            assertTrue(plan.feasible(), () -> "amount=" + amount + " missing=" + plan.missing());
            assertEquals(Sat.ceilDiv(3L * amount, 2L),
                    firingsOf(plan, feedback.makeB()), "amount=" + amount);
            assertEquals(amount, firingsOf(plan, feedback.makeTarget()), "amount=" + amount);
            assertEquals(minimumSeed, plan.usedStock().get("A"), "amount=" + amount);
            assertTrue(plan.missing().isEmpty(), "amount=" + amount);
        }
    }

    @Test
    void weightedPositiveFeedbackReportsOnlyTheMissingExecutableSeed() {
        for (long amount : List.of(2L, 3L, 8L)) {
            long minimumSeed = weightedPositiveMinimumSeed(amount);
            WeightedPositiveFeedback feedback = weightedPositiveFeedback(minimumSeed - 1L);

            CraftPlan<String> plan = CraftPlannerV2.plan(feedback.graph(), "T", amount);

            assertFalse(plan.feasible(), "amount=" + amount);
            assertEquals(Map.of("A", 1L), plan.missing(), "amount=" + amount);
            assertEquals(minimumSeed - 1L, plan.usedStock().get("A"), "amount=" + amount);
        }
    }

    @Test
    void weightedPositiveFeedbackSeedCompetesWithSiblingConsumption() {
        WeightedPositiveFeedback feedback = weightedPositiveFeedback(504L);
        CraftGraph<String> shortGraph = CraftGraph.<String>builder()
                .pattern(feedback.makeB())
                .pattern(feedback.makeTarget())
                .pattern("side", 1, List.of(CraftInput.of("A", 5)))
                .pattern("root", 1, List.of(
                        CraftInput.of("T", 1_000), CraftInput.of("side", 1)))
                .stock("A", 504)
                .build();
        CraftGraph<String> completeGraph = CraftGraph.<String>builder()
                .pattern(feedback.makeB())
                .pattern(feedback.makeTarget())
                .pattern("side", 1, List.of(CraftInput.of("A", 5)))
                .pattern("root", 1, List.of(
                        CraftInput.of("T", 1_000), CraftInput.of("side", 1)))
                .stock("A", 505)
                .build();

        CraftPlan<String> shortPlan = CraftPlannerV2.plan(shortGraph, "root", 1);
        CraftPlan<String> completePlan = CraftPlannerV2.plan(completeGraph, "root", 1);

        assertFalse(shortPlan.feasible());
        assertEquals(Set.of("A"), shortPlan.missing().keySet());
        assertTrue(shortPlan.missing().get("A") > 0L);
        assertTrue(completePlan.feasible(), () -> "missing=" + completePlan.missing());
        assertEquals(505L, completePlan.usedStock().get("A"));
    }

    @Test
    void weightedPositiveFeedbackScalesWithoutPerFiringSimulation() {
        long amount = 1_000_000_000L;
        long minimumSeed = weightedPositiveMinimumSeed(amount);
        WeightedPositiveFeedback feedback = weightedPositiveFeedback(minimumSeed);

        CraftPlan<String> plan = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> CraftPlannerV2.plan(feedback.graph(), "T", amount));

        assertTrue(plan.feasible(), () -> "missing=" + plan.missing());
        assertEquals(1_500_000_000L, firingsOf(plan, feedback.makeB()));
        assertEquals(amount, firingsOf(plan, feedback.makeTarget()));
        assertEquals(minimumSeed, plan.usedStock().get("A"));
    }

    @Test
    void weightedFeedbackKeepsExternalConsumablesOutsideTheStateCycle() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 3)), "3A_to_2B");
        CraftPattern<String> makeTarget = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("B", 3), CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("A", 4)), "3B_raw_to_T_4A");
        CraftGraph<String> complete = CraftGraph.<String>builder()
                .pattern(makeB).pattern(makeTarget)
                .stock("A", 6).stock("raw", 2).build();
        CraftGraph<String> shortSeed = CraftGraph.<String>builder()
                .pattern(makeB).pattern(makeTarget)
                .stock("A", 1).stock("raw", 2).build();

        CraftPlan<String> completePlan = CraftPlannerV2.plan(complete, "T", 2);
        PlanningResult<String> first = CraftPlannerV2.planDetailed(shortSeed, "T", 2);
        PlanningResult<String> refilled = CraftPlannerV2.planDetailed(
                shortSeed.withAdditionalStock(first.plan().missing()), "T", 2);

        assertTrue(completePlan.feasible());
        assertEquals(Map.of("A", 6L, "raw", 2L), completePlan.usedStock());
        assertEquals(3L, firingsOf(completePlan, makeB));
        assertEquals(2L, firingsOf(completePlan, makeTarget));
        assertFalse(first.plan().feasible());
        assertEquals(Map.of("A", 5L), first.plan().missing());
        assertTrue(refilled.plan().feasible(), () -> "missing=" + refilled.plan().missing());
        assertEquals(1, refilled.diagnostics().planRuns());
        assertEquals(0, refilled.diagnostics().consumedSearchBudget());
        assertEquals(0, refilled.diagnostics().consumedResolutionBudget());
        assertEquals(0, refilled.diagnostics().consumedFallbackBudget());
    }

    @Test
    void threeStateWeightedFeedbackRecomputesFromReportedMissingWithoutContext() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 2)), "2A_to_B");
        CraftPattern<String> makeC = new CraftPattern<>(
                "C", 1, List.of(CraftInput.of("B", 3)), "3B_to_C");
        CraftPattern<String> makeTarget = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("C", 2)),
                List.of(CraftOutput.of("A", 5)), "2C_to_T_5A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(makeC).pattern(makeTarget)
                .stock("A", 14).build();

        PlanningResult<String> first = CraftPlannerV2.planDetailed(graph, "T", 2);
        PlanningResult<String> refilled = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> CraftPlannerV2.planDetailed(
                        graph.withAdditionalStock(first.plan().missing()), "T", 2));

        assertFalse(first.plan().feasible());
        assertEquals(Map.of("A", 5L), first.plan().missing());
        assertTrue(refilled.plan().feasible(), () -> "missing=" + refilled.plan().missing());
        assertEquals(19L, refilled.plan().usedStock().get("A"));
        assertEquals(12L, firingsOf(refilled.plan(), makeB));
        assertEquals(4L, firingsOf(refilled.plan(), makeC));
        assertEquals(2L, firingsOf(refilled.plan(), makeTarget));
        assertEquals(1, refilled.diagnostics().planRuns());
        assertEquals(0, refilled.diagnostics().consumedSearchBudget());
        assertEquals(0, refilled.diagnostics().consumedResolutionBudget());
        assertEquals(0, refilled.diagnostics().consumedFallbackBudget());
    }

    @Test
    void nonGrowingPetriFallbackRecomputesFromReportedMissingWithoutContext() {
        CraftPattern<String> split = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 2)),
                List.of(CraftOutput.of("C", 1)), "2A_to_B_C");
        CraftPattern<String> target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("B", 1), CraftInput.of("C", 1)),
                List.of(CraftOutput.of("A", 1)), "B_C_to_T_A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(split).pattern(target).stock("A", 2).build();

        PlanningResult<String> first = CraftPlannerV2.planDetailed(graph, "T", 2);
        PlanningResult<String> refilled = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> CraftPlannerV2.planDetailed(
                        graph.withAdditionalStock(first.plan().missing()), "T", 2));

        assertFalse(first.plan().feasible());
        assertEquals(Map.of("A", 1L), first.plan().missing());
        assertTrue(refilled.plan().feasible(), () -> "missing=" + refilled.plan().missing());
        assertEquals(3L, refilled.plan().usedStock().get("A"));
        assertEquals(2L, firingsOf(refilled.plan(), split));
        assertEquals(2L, firingsOf(refilled.plan(), target));
        assertEquals(1, refilled.diagnostics().planRuns());
        assertEquals(0, refilled.diagnostics().consumedSearchBudget());
        assertEquals(0, refilled.diagnostics().consumedFallbackBudget());
        assertFalse(refilled.plan().budgetExhausted());
    }

    @Test
    void nonGrowingPetriFallbackIsBoundedAtBillionScale() {
        long amount = 1_000_000_000L;
        CraftPattern<String> split = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 2)),
                List.of(CraftOutput.of("C", 1)), "2A_to_B_C");
        CraftPattern<String> target = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("B", 1), CraftInput.of("C", 1)),
                List.of(CraftOutput.of("A", 1)), "B_C_to_T_A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(split).pattern(target).stock("A", amount).build();

        PlanningResult<String> first = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> CraftPlannerV2.planDetailed(graph, "T", amount));
        PlanningResult<String> refilled = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> CraftPlannerV2.planDetailed(
                        graph.withAdditionalStock(first.plan().missing()), "T", amount));

        assertFalse(first.plan().feasible());
        assertEquals(Map.of("A", 1L), first.plan().missing(),
                "the repeatable startup prefix loses exactly one A per completed round");
        assertTrue(refilled.plan().feasible(), () -> "missing=" + refilled.plan().missing());
        assertEquals(amount + 1L, refilled.plan().usedStock().get("A"));
        assertEquals(amount, firingsOf(refilled.plan(), split));
        assertEquals(amount, firingsOf(refilled.plan(), target));
        assertEquals(1, refilled.diagnostics().planRuns());
        assertFalse(refilled.plan().budgetExhausted());
    }

    private static long weightedPositiveMinimumSeed(long amount) {
        return Sat.add(Sat.ceilDiv(amount, 2L), 5L);
    }

    private static WeightedPositiveFeedback weightedPositiveFeedback(long stockA) {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 3)), "3A_to_2B");
        CraftPattern<String> makeTarget = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("B", 3)),
                List.of(CraftOutput.of("A", 4)), "3B_to_T_4A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB)
                .pattern(makeTarget)
                .stock("A", stockA)
                .build();
        return new WeightedPositiveFeedback(graph, makeB, makeTarget);
    }

    private record WeightedPositiveFeedback(
            CraftGraph<String> graph,
            CraftPattern<String> makeB,
            CraftPattern<String> makeTarget) {
    }

    @Test
    void partialFeedbackMayBootstrapFromTheReturnedState() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 2)),
                List.of(CraftOutput.of("C", 1)), "makeB");
        CraftPattern<String> recoverA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("C", 1)), "recoverA");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(recoverA).stock("A", 2).stock("C", 1).build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 4);

        assertTrue(plan.feasible());
        assertEquals(2L, plan.usedStock().get("A"));
        assertEquals(1L, plan.usedStock().get("C"));
    }

    @Test
    void partialFeedbackRequestSmallerThanRatioCycleNeedsOnlyItsActualInputBatch() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 1)),
                List.of(CraftOutput.of("C", 2)), "makeB");
        CraftPattern<String> recoverA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("C", 3)), "recoverA");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(recoverA).stock("C", 3).build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 2);

        assertTrue(plan.feasible());
        assertEquals(3L, plan.usedStock().get("C"));
        assertEquals(1L, firingsOf(plan, makeB));
        assertEquals(1L, firingsOf(plan, recoverA));
    }

    @Test
    void balancedRawFeedbackRemainsAnOrdinaryFinitePlan() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)),
                List.of(CraftOutput.of("C", 1)), "makeB");
        CraftPattern<String> recoverA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("C", 1)), "recoverA");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(recoverA).stock("A", 1).build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 100);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("A"));
        assertEquals(100L, firingsOf(plan, makeB));
        assertEquals(99L, firingsOf(plan, recoverA));
    }

    @Test
    void multiHopBalancedRawFeedbackCannotBootstrapItself() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 1)), "A_to_2B");
        CraftPattern<String> makeE = new CraftPattern<>(
                "E", 1, List.of(CraftInput.of("B", 2), CraftInput.of("C", 1)),
                List.of(CraftOutput.of("D", 1)), "2B_C_to_E_D");
        CraftPattern<String> recoverA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("D", 1)), "D_to_A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(makeE).pattern(recoverA)
                .stock("C", 8)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "E", 8);

        assertFalse(plan.feasible(), "a balanced algebraic flow still needs one initial state");
        // Supplying one D can execute D->A before the round. As a feedback byproduct it is outside
        // the root's input-demand hierarchy, so it loses no higher-priority Missing tier.
        assertEquals(Map.of("D", 1L), plan.missing());
        assertEquals(8L, plan.usedStock().get("C"));
        assertEquals(8L, firingsOf(plan, makeB));
        assertEquals(8L, firingsOf(plan, makeE));
        assertEquals(8L, firingsOf(plan, recoverA));
    }

    @Test
    void multiHopBalancedRawFeedbackUsesOneNetworkSeed() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 2, List.of(CraftInput.of("A", 1)), "A_to_2B");
        CraftPattern<String> makeE = new CraftPattern<>(
                "E", 1, List.of(CraftInput.of("B", 2), CraftInput.of("C", 1)),
                List.of(CraftOutput.of("D", 1)), "2B_C_to_E_D");
        CraftPattern<String> recoverA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("D", 1)), "D_to_A");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(makeE).pattern(recoverA)
                .stock("A", 1)
                .stock("C", 8)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "E", 8);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("A"));
        assertEquals(8L, plan.usedStock().get("C"));
        assertEquals(8L, firingsOf(plan, makeB));
        assertEquals(8L, firingsOf(plan, makeE));
        assertEquals(7L, firingsOf(plan, recoverA));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void balancedSelfReturnCraftsOneSeedFromAnAcyclicProducerFirst() {
        CraftPattern<String> makeSeed = new CraftPattern<>(
                "seed", 1, List.of(CraftInput.of("raw", 1)), "raw_to_seed");
        CraftPattern<String> catalyzed = new CraftPattern<>(
                "product", 1, List.of(CraftInput.of("seed", 1)),
                List.of(CraftOutput.of("seed", 1)), "seed_to_product_and_seed");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeSeed)
                .pattern(catalyzed)
                .stock("raw", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "product", 1);

        assertTrue(plan.feasible(), () -> plan.toString());
        assertEquals(1L, plan.usedStock().get("raw"));
        assertEquals(1L, firingsOf(plan, makeSeed));
        assertEquals(1L, firingsOf(plan, catalyzed));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void positiveRawFeedbackIsNotSolvedOutsideAContractedLoopPattern() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)),
                List.of(CraftOutput.of("C", 1)), "makeB");
        CraftPattern<String> duplicateA = new CraftPattern<>(
                "A", 2, List.of(CraftInput.of("C", 1)), "duplicateA");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(duplicateA).stock("A", 1).build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 3);

        assertFalse(plan.feasible(), "raw gain feedback must be compiled into a closed-loop macro");
        assertEquals(1L, plan.missing().get("C"));
    }

    @Test
    void positiveFeedbackRefillStillWorksFromIndependentFiniteStock() {
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)),
                List.of(CraftOutput.of("C", 1)), "makeB");
        CraftPattern<String> duplicateA = new CraftPattern<>(
                "A", 2, List.of(CraftInput.of("C", 1)), "duplicateA");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(makeB).pattern(duplicateA)
                .stock("A", 1).stock("C", 1).build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 3);

        assertTrue(plan.feasible(), "existing C is a finite input, not feedback from makeB");
        assertEquals(1L, plan.usedStock().get("A"));
        assertEquals(1L, plan.usedStock().get("C"));
    }

    @Test
    void contractedPureGainHasUnboundedCapacityButKeepsFiniteFirings() {
        long amount = Integer.MAX_VALUE;
        CraftPattern<String> contracted = new CraftPattern<>(
                "seed", 1, List.of(CraftInput.returned("seed", 1)), "contractedClosedLoop");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(contracted).stock("seed", 1).build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "seed", amount);

        assertTrue(plan.feasible());
        assertEquals(amount, firingsOf(plan, contracted));
        assertEquals(1L, plan.usedStock().get("seed"));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void pureSelfGainCraftsItsCatalystWithANonLoopPatternFirst() {
        long amount = Integer.MAX_VALUE;
        CraftPattern<String> seedFromA = new CraftPattern<>(
                "seed", 1, List.of(CraftInput.of("A", 1)), "A_to_seed");
        CraftPattern<String> contracted = new CraftPattern<>(
                "seed", 1, List.of(CraftInput.returned("seed", 1)), "contractedSelfGain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(seedFromA)
                .pattern(contracted)
                .stock("A", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "seed", amount);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("A"));
        assertEquals(1L, firingsOf(plan, seedFromA));
        assertEquals(amount, firingsOf(plan, contracted));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void oneSearchCandidateStillBuildsAContractedLoopSeedWithoutFalseCutoff() {
        CraftPattern<String> seedFromA = new CraftPattern<>(
                "seed", 1, List.of(CraftInput.of("A", 1)), "A_to_seed");
        CraftPattern<String> contracted = new CraftPattern<>(
                "seed", 1, List.of(CraftInput.returned("seed", 1)), "contractedSelfGain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(seedFromA)
                .pattern(contracted)
                .stock("A", 1)
                .build();

        PlanningResult<String> planning =
                CraftPlannerV2.planDetailed(graph, "seed", 1_000, 1, 1);

        assertTrue(planning.plan().feasible());
        assertFalse(planning.plan().budgetExhausted());
        assertFalse(planning.diagnostics().searchCutoff(),
                "using the one admitted candidate is not a budget cutoff");
        assertEquals(0, planning.diagnostics().consumedSearchBudget(),
                "the fixed integer vector needs validation, not another route search");
        assertEquals(1, planning.diagnostics().lowWidthSolved());
        assertEquals(1, planning.diagnostics().planRuns());
        assertEquals(1L, firingsOf(planning.plan(), seedFromA));
        assertEquals(1_000L, firingsOf(planning.plan(), contracted));
    }

    @Test
    void contractedGainCannotUseItsOwnSecondaryOutputAsTheSeed() {
        CraftPattern<String> contracted = new CraftPattern<>(
                "product", 1, List.of(CraftInput.returned("seed", 1)),
                List.of(CraftOutput.of("seed", 1)), "contractedClosedLoop");
        CraftGraph<String> unseeded = CraftGraph.<String>builder().pattern(contracted).build();
        CraftGraph<String> seeded = CraftGraph.<String>builder()
                .pattern(contracted).stock("seed", 1).build();

        CraftPlan<String> missingSeed = CraftPlannerV2.plan(unseeded, "product", Integer.MAX_VALUE);
        CraftPlan<String> availableSeed = CraftPlannerV2.plan(seeded, "product", Integer.MAX_VALUE);

        assertFalse(missingSeed.feasible());
        assertEquals(1L, missingSeed.missing().get("seed"));
        assertTrue(availableSeed.feasible());
        assertEquals(1L, availableSeed.usedStock().get("seed"));
        assertEquals(Integer.MAX_VALUE, firingsOf(availableSeed, contracted));
    }

    @Test
    void emptySeedPoolCraftsOneSeedBeforeRunningContractedGainLoop() {
        CraftPattern<String> seedFromA = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A_to_B_seed");
        // Contracted form of (B -> 2C; C -> B): one reusable B seed yields net +1 C.
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "C", 1, List.of(CraftInput.returned("B", 1)), "contracted_B_C_loop");
        CraftPattern<String> makeD = new CraftPattern<>(
                "D", 1, List.of(CraftInput.of("C", 2)), "2C_to_D");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(seedFromA)
                .pattern(contractedGain)
                .pattern(makeD)
                .stock("A", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "D", 1_000);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("A"));
        assertEquals(1L, firingsOf(plan, seedFromA));
        assertEquals(2_000L, firingsOf(plan, contractedGain));
        assertEquals(1_000L, firingsOf(plan, makeD));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void normalStockBootstrapsContractedGainBeforeProducingTheRequestedAlternateState() {
        var source = new ReusableStockSource("tianshu", "loop");
        CraftPattern<String> aToB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A_to_B");
        CraftPattern<String> rawBToTwoA = new CraftPattern<>(
                "A", 2, List.of(CraftInput.of("B", 1)), "B_to_2A_raw");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "A", 1, List.of(CraftInput.returnedFrom("A", 1, source)),
                "contracted_A_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(aToB)
                .pattern(rawBToTwoA)
                .pattern(contractedGain)
                .stock("A", 1)
                .reusableStockRoute(source, "A", List.of("A"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 1_000);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("A"));
        assertEquals(1_000L, firingsOf(plan, contractedGain));
        assertEquals(1_000L, firingsOf(plan, aToB));
        assertEquals(0L, firingsOf(plan, rawBToTwoA),
                "the cut raw feedback edge must not bootstrap itself from the requested B");
        assertTrue(plan.usedReusableStock().isEmpty());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void hostSeedStillTakesPriorityForTheSameBidirectionalCut() {
        var source = new ReusableStockSource("tianshu", "loop");
        CraftPattern<String> aToB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A_to_B");
        CraftPattern<String> rawBToTwoA = new CraftPattern<>(
                "A", 2, List.of(CraftInput.of("B", 1)), "B_to_2A_raw");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "A", 1, List.of(CraftInput.returnedFrom("A", 1, source)),
                "contracted_A_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(aToB)
                .pattern(rawBToTwoA)
                .pattern(contractedGain)
                .reusableStock("tianshu", "A", 1)
                .reusableStockRoute(source, "A", List.of("A"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 1_000);

        assertTrue(plan.feasible());
        assertEquals(1_000L, firingsOf(plan, contractedGain));
        assertEquals(1_000L, firingsOf(plan, aToB));
        assertEquals(0L, firingsOf(plan, rawBToTwoA));
        assertEquals(1L, plan.usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
        assertTrue(plan.usedStock().isEmpty());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void requestedReturnedStateBootstrapsContractedGainFromOutputStock() {
        var source = new ReusableStockSource("tianshu", "loop");
        CraftPattern<String> aToB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A_to_B");
        // The selected reusable loop state is B, while the contracted loop produces net +1 A.
        // Requesting B must keep one A aside, convert it into the initial B seed, grow A through
        // the loop, then run A_to_B for the requested final output.
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "A", 1, List.of(CraftInput.returnedFrom("B", 1, source)),
                "contracted_B_seed_A_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(aToB)
                .pattern(contractedGain)
                .stock("A", 1)
                .reusableStockRoute(source, "B", List.of("B"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 1_000);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("A"));
        assertEquals(1_001L, firingsOf(plan, aToB),
                "one conversion bootstraps the returned B seed before producing final B");
        assertEquals(1_000L, firingsOf(plan, contractedGain));
        assertEquals(1_001L, plan.grossDemand().get("A"));
        assertTrue(plan.usedReusableStock().isEmpty());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void requestedReturnedStateUsesHostSeedWithoutExtraBootstrapConversion() {
        var source = new ReusableStockSource("tianshu", "loop");
        CraftPattern<String> aToB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A_to_B");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "A", 1, List.of(CraftInput.returnedFrom("B", 1, source)),
                "contracted_B_seed_A_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(aToB)
                .pattern(contractedGain)
                .reusableStock("tianshu", "B", 1)
                .reusableStockRoute(source, "B", List.of("B"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 1_000);

        assertTrue(plan.feasible());
        assertEquals(1_000L, firingsOf(plan, aToB));
        assertEquals(1_000L, firingsOf(plan, contractedGain));
        assertEquals(1L, plan.usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
        assertTrue(plan.usedStock().isEmpty());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void requestedReturnedStateWithoutHostOrOutputStockRemainsMissing() {
        var source = new ReusableStockSource("tianshu", "loop");
        CraftPattern<String> aToB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A_to_B");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "A", 1, List.of(CraftInput.returnedFrom("B", 1, source)),
                "contracted_B_seed_A_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(aToB)
                .pattern(contractedGain)
                .reusableStockRoute(source, "B", List.of("B"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 1_000);

        assertFalse(plan.feasible());
        assertEquals(1L, plan.missing().get("B"));
        assertTrue(plan.usedStock().isEmpty());
        assertTrue(plan.usedReusableStock().isEmpty());
    }

    @Test
    void partialOutputStockBootstrapsOnlyTheClosedLoopShortfall() {
        var source = new ReusableStockSource("tianshu", "certus_loop");
        CraftPattern<String> crystalToDust = new CraftPattern<>(
                "dust", 1, List.of(CraftInput.of("crystal", 1)), "crush_certus");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "crystal", 1, List.of(CraftInput.returnedFrom("dust", 1, source)),
                "contracted_certus_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(crystalToDust)
                .pattern(contractedGain)
                .stock("crystal", 5_440)
                .reusableStockRoute(source, "dust", List.of("dust"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "dust", 10_048);

        assertTrue(plan.feasible());
        assertEquals(5_440L, plan.usedStock().get("crystal"));
        assertEquals(10_049L, firingsOf(plan, crystalToDust));
        assertEquals(4_609L, firingsOf(plan, contractedGain));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void downstreamChargedOutputCanBootstrapTheLoopThroughItsCutConverter() {
        var source = new ReusableStockSource("tianshu", "certus_loop");
        CraftPattern<String> crystalToDust = new CraftPattern<>(
                "dust", 1, List.of(CraftInput.of("crystal", 1)), "crush_certus");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "crystal", 1, List.of(CraftInput.returnedFrom("dust", 1, source)),
                "contracted_certus_gain");
        CraftPattern<String> chargeCrystal = new CraftPattern<>(
                "charged_crystal", 1, List.of(CraftInput.of("crystal", 1)),
                "charge_certus");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(crystalToDust)
                .pattern(contractedGain)
                .pattern(chargeCrystal)
                .stock("crystal", 1)
                .reusableStockRoute(source, "dust", List.of("dust"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "charged_crystal", 1_000);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("crystal"));
        assertEquals(1L, firingsOf(plan, crystalToDust));
        assertEquals(1_000L, firingsOf(plan, contractedGain));
        assertEquals(1_000L, firingsOf(plan, chargeCrystal));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void downstreamOutputBootstrapsEveryReturnedStateOfTheSameContractedLoop() {
        var source = new ReusableStockSource("tianshu", "certus_loop");
        CraftPattern<String> crystalToDust = new CraftPattern<>(
                "dust", 64, List.of(CraftInput.of("crystal", 64)), "crush_certus");
        CraftPattern<String> chargeCrystal = new CraftPattern<>(
                "charged_crystal", 64, List.of(CraftInput.of("crystal", 64)),
                "charge_certus");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "crystal", 128,
                List.of(
                        CraftInput.returnedFrom("charged_crystal", 64, source),
                        CraftInput.returnedFrom("dust", 64, source)),
                "contracted_certus_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(crystalToDust)
                .pattern(chargeCrystal)
                .pattern(contractedGain)
                .stock("crystal", 5_120)
                .reusableStockRoute(source, "charged_crystal", List.of("charged_crystal"))
                .reusableStockRoute(source, "dust", List.of("dust"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "charged_crystal", 11_000);

        assertTrue(plan.feasible());
        assertEquals(5_120L, plan.usedStock().get("crystal"));
        assertEquals(1L, firingsOf(plan, crystalToDust));
        assertEquals(173L, firingsOf(plan, chargeCrystal),
                "172 final batches plus one charged-state bootstrap batch");
        assertEquals(47L, firingsOf(plan, contractedGain));
        assertTrue(plan.usedReusableStock().isEmpty());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void downstreamOutputCanConvertAnotherHostStateIntoItsMissingSeed() {
        var source = new ReusableStockSource("tianshu", "certus_loop");
        CraftPattern<String> crystalToDust = new CraftPattern<>(
                "dust", 64, List.of(CraftInput.of("crystal", 64)), "crush_certus");
        CraftPattern<String> chargeCrystal = new CraftPattern<>(
                "charged_crystal", 64,
                List.of(
                        CraftInput.of("crystal", 64),
                        CraftInput.of("water", 1_000)),
                "charge_certus");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "crystal", 128,
                List.of(
                        CraftInput.returnedFrom("charged_crystal", 64, source),
                        CraftInput.returnedFrom("dust", 64, source),
                        CraftInput.of("water", 3_000)),
                "contracted_certus_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(crystalToDust)
                .pattern(chargeCrystal)
                .pattern(contractedGain)
                .stock("water", 1_000_000)
                .reusableStock("tianshu", "charged_crystal", 160)
                .reusableStock("tianshu", "crystal", 192)
                .reusableStockRoute(source, "charged_crystal", List.of("charged_crystal"))
                .reusableStockRoute(source, "dust", List.of("dust"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "charged_crystal", 19_000);

        assertTrue(plan.feasible());
        assertEquals(1L, firingsOf(plan, crystalToDust));
        assertEquals(297L, firingsOf(plan, chargeCrystal));
        assertEquals(149L, firingsOf(plan, contractedGain));
        assertEquals(744_000L, plan.usedStock().get("water"));
        assertFalse(plan.usedStock().containsKey("crystal"),
                "private bootstrap stock must not be charged to ordinary network inventory");
        assertEquals(128L, plan.usedReusableStock().values().stream()
                .mapToLong(Long::longValue).sum());
        assertTrue(plan.usedReusableStock().keySet().stream().anyMatch(
                usage -> usage.actualKey().equals("crystal")
                        && usage.routingScope() instanceof ReusableBootstrapRoute<?> route
                        && route.returnedSeedKey().equals("dust")));
        assertTrue(plan.missing().isEmpty());
    }

    /**
     * The AdvancedAE reaction-chamber charger consumes water next to the loop state
     * (64 certus + 1000 water -> 64 charged). A multi-input converter must still prove the
     * feedback-seed bootstrap when no host seed is stored; its auxiliary inputs are obtained
     * normally for the bootstrap firing.
     */
    @Test
    void multiInputConverterBootstrapsTheLoopSeed() {
        var source = new ReusableStockSource("tianshu", "certus_loop");
        CraftPattern<String> chargeCrystal = new CraftPattern<>(
                "charged_crystal", 64,
                List.of(CraftInput.of("crystal", 64), CraftInput.of("water", 1_000)),
                "reaction_charge");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "crystal", 64,
                List.of(
                        CraftInput.returnedFrom("charged_crystal", 32, source),
                        CraftInput.of("dust", 32),
                        CraftInput.of("water", 2_500)),
                List.of(CraftOutput.of("charged_crystal", 32)),
                "contracted_certus_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(chargeCrystal)
                .pattern(contractedGain)
                .stock("crystal", 128)
                .stock("dust", 100_000)
                .stock("water", 100_000)
                .reusableStockRoute(source, "charged_crystal", List.of("charged_crystal"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "charged_crystal", 200);

        assertTrue(plan.feasible());
        assertEquals(5L, firingsOf(plan, chargeCrystal),
                "4 final batches plus one charged-state bootstrap batch");
        assertEquals(3L, firingsOf(plan, contractedGain));
        assertEquals(128L, plan.usedStock().get("crystal"));
        assertEquals(12_500L, plan.usedStock().get("water"),
                "bootstrap water (1000) + loop water (7500) + final batches (4000)");
        assertEquals(96L, plan.usedStock().get("dust"));
        assertTrue(plan.missing().isEmpty());
    }

    /** Same proof from the other DFS orientation: the multi-input converter is the cut edge. */
    @Test
    void multiInputConverterBootstrapsWhenConverterIsTheCutEdge() {
        var source = new ReusableStockSource("tianshu", "certus_loop");
        CraftPattern<String> chargeCrystal = new CraftPattern<>(
                "charged_crystal", 64,
                List.of(CraftInput.of("crystal", 64), CraftInput.of("water", 1_000)),
                "reaction_charge");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "crystal", 64,
                List.of(
                        CraftInput.returnedFrom("charged_crystal", 32, source),
                        CraftInput.of("dust", 32),
                        CraftInput.of("water", 2_500)),
                "contracted_certus_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(chargeCrystal)
                .pattern(contractedGain)
                .stock("crystal", 128)
                .stock("dust", 100_000)
                .stock("water", 8_500)
                .reusableStockRoute(source, "charged_crystal", List.of("charged_crystal"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "crystal", 200);

        assertTrue(plan.feasible());
        assertEquals(1L, firingsOf(plan, chargeCrystal));
        assertEquals(3L, firingsOf(plan, contractedGain));
        assertEquals(8_500L, plan.usedStock().get("water"),
                "bootstrap water (1000) + loop water (7500) exactly exhaust stock");
        assertTrue(plan.missing().isEmpty());
    }

    /** A bootstrap whose auxiliary material cannot be covered fails closed as a missing seed. */
    @Test
    void multiInputConverterAuxiliaryShortfallLeavesSeedMissing() {
        var source = new ReusableStockSource("tianshu", "certus_loop");
        CraftPattern<String> chargeCrystal = new CraftPattern<>(
                "charged_crystal", 64,
                List.of(CraftInput.of("crystal", 64), CraftInput.of("water", 1_000)),
                "reaction_charge");
        CraftPattern<String> contractedGain = new CraftPattern<>(
                "crystal", 64,
                List.of(
                        CraftInput.returnedFrom("charged_crystal", 32, source),
                        CraftInput.of("dust", 32),
                        CraftInput.of("water", 2_500)),
                "contracted_certus_gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(chargeCrystal)
                .pattern(contractedGain)
                .stock("crystal", 128)
                .stock("dust", 100_000)
                .stock("water", 500)
                .reusableStockRoute(source, "charged_crystal", List.of("charged_crystal"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "crystal", 200);

        assertFalse(plan.feasible());
        assertEquals(32L, plan.missing().get("charged_crystal"),
                "the seed itself is reported missing when its bootstrap cannot run");
    }

    @Test
    void multiInputBootstrapTriesAnotherConverterWhenFirstAuxiliaryIsMissing() {
        var source = new ReusableStockSource("tianshu", "loop");
        CraftPattern<String> unavailableConverter = new CraftPattern<>(
                "seed", 1,
                List.of(CraftInput.of("state", 1), CraftInput.of("missing_auxiliary", 1)),
                "unavailable_converter");
        CraftPattern<String> availableConverter = new CraftPattern<>(
                "seed", 1,
                List.of(CraftInput.of("state", 1), CraftInput.of("available_auxiliary", 1)),
                "available_converter");
        CraftPattern<String> gain = new CraftPattern<>(
                "state", 2,
                List.of(CraftInput.returnedFrom("seed", 1, source)),
                "gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(unavailableConverter)
                .pattern(availableConverter)
                .pattern(gain)
                .stock("state", 1)
                .stock("available_auxiliary", 1)
                .reusableStockRoute(source, "seed", List.of("seed"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "state", 2);

        assertTrue(plan.feasible(), "the second converter provides a valid bootstrap");
        assertEquals(0L, firingsOf(plan, unavailableConverter));
        assertEquals(1L, firingsOf(plan, availableConverter));
        assertEquals(1L, firingsOf(plan, gain));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void directFeedbackCutKeepsEveryBootstrapConverterCandidate() {
        var source = new ReusableStockSource("tianshu", "loop");
        CraftPattern<String> unavailableConverter = new CraftPattern<>(
                "seed", 1,
                List.of(CraftInput.of("state", 1), CraftInput.of("missing_auxiliary", 1)),
                "unavailable_converter");
        CraftPattern<String> availableConverter = new CraftPattern<>(
                "seed", 1,
                List.of(CraftInput.of("state", 1), CraftInput.of("available_auxiliary", 1)),
                "available_converter");
        CraftPattern<String> gain = new CraftPattern<>(
                "state", 2,
                List.of(CraftInput.returnedFrom("seed", 1, source)),
                "gain");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(unavailableConverter)
                .pattern(availableConverter)
                .pattern(gain)
                .stock("state", 1)
                .stock("available_auxiliary", 3)
                .reusableStockRoute(source, "seed", List.of("seed"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "seed", 2);

        assertTrue(plan.feasible(), "the direct cut must retain the second converter");
        assertEquals(0L, firingsOf(plan, unavailableConverter));
        assertEquals(3L, firingsOf(plan, availableConverter));
        assertEquals(1L, firingsOf(plan, gain));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void alternateLoopStateCanBeCraftedIntoTheDefaultSeedBeforeStartup() {
        CraftPattern<String> bFromA = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 1)), "A_to_B");
        CraftPattern<String> cFromB = new CraftPattern<>(
                "C", 2, List.of(CraftInput.of("B", 1)), "B_to_2C");
        // Same (B -> 2C; C -> B) loop, but its selected/default reusable seed is C.
        CraftPattern<String> contractedWithCSeed = new CraftPattern<>(
                "C", 1, List.of(CraftInput.returned("C", 1)), "contracted_C_seed_loop");
        CraftPattern<String> makeD = new CraftPattern<>(
                "D", 1, List.of(CraftInput.of("C", 2)), "2C_to_D");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(bFromA)
                .pattern(cFromB)
                .pattern(contractedWithCSeed)
                .pattern(makeD)
                .stock("A", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "D", 1_000);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("A"));
        assertEquals(1L, firingsOf(plan, bFromA));
        assertEquals(1L, firingsOf(plan, cFromB));
        assertEquals(2_000L, firingsOf(plan, contractedWithCSeed));
        assertEquals(1_000L, firingsOf(plan, makeD));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void containerCycleCanBootstrapFromReturnedStateButNotFromNothing() {
        CraftPattern<String> makeP = new CraftPattern<>(
                "P", 1, List.of(CraftInput.consumedReturning("full", 1, "empty")),
                List.of(CraftOutput.of("empty", 1)), "makeP");
        CraftPattern<String> refill = new CraftPattern<>(
                "full", 1, List.of(CraftInput.of("empty", 1), CraftInput.of("water", 1)), "refill");

        CraftGraph<String> withSeed = CraftGraph.<String>builder()
                .pattern(makeP).pattern(refill).stock("empty", 1).stock("water", 100).build();
        CraftGraph<String> withoutSeed = CraftGraph.<String>builder()
                .pattern(makeP).pattern(refill).stock("water", 100).build();

        CraftPlan<String> seeded = CraftPlannerV2.plan(withSeed, "P", 10);
        CraftPlan<String> unseeded = CraftPlannerV2.plan(withoutSeed, "P", 10);

        assertTrue(seeded.feasible());
        assertEquals(1L, seeded.usedStock().get("empty"));
        assertFalse(unseeded.feasible());
        assertEquals(1L, unseeded.missing().values().stream().mapToLong(Long::longValue).sum());
        assertTrue(unseeded.missing().containsKey("full")
                || unseeded.missing().containsKey("empty"));
    }

    @Test
    void decompressCycleMakesIngotsFromBlockStock() {
        // Same pair, opposite direction: target = ingot, stock = blocks. Now the compress recipe is the
        // back-edge that gets cut, so ingots come from decompressing blocks.
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("block", 1, List.of(CraftInput.of("ingot", 9)))
                .pattern("ingot", 9, List.of(CraftInput.of("block", 1)))
                .stock("block", 10)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "ingot", 20);
        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertEquals(3L, plan.usedStock().get("block")); // ceil(20/9) = 3 blocks -> 27 ingots
        assertTrue(plan.missing().isEmpty());
    }

    /**
     * A fully infeasible tree where every item has several materially distinct recipes would explode
     * without memoization. A failed child proof is reused only after trail rollback restores the exact
     * same availability state, so work stays linear without a whole-plan cutoff.
     */
    @Test
    void boundedSearchDoesNotBlowUp() {
        int depth = 100;
        int alternatives = 4;
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        for (int i = 0; i < depth; i++) {
            for (int alternative = 0; alternative < alternatives; alternative++) {
                b.pattern(new CraftPattern<>(
                        "A" + i,
                        1,
                        List.of(CraftInput.of("A" + (i + 1), 1)),
                        List.of(CraftOutput.of("distinct-waste-" + i + "-" + alternative, 1)),
                        "A" + i + "_" + alternative));
            }
        }
        // A{depth} is a raw leaf with no stock -> the whole thing is infeasible.

        long requested = 1_000_000_000L;
        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "A0", requested);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertFalse(plan.budgetExhausted(), "exact failure reuse should complete within the global budget");
        assertEquals(requested, plan.missing().get("A" + depth),
                "the final greedy route must still report its concrete raw-leaf shortfall");
        assertTrue(plan.itemsProcessed() <= depth * alternatives,
                "exact-state failure memo must prevent repeated subtree expansion: "
                        + plan.itemsProcessed());
    }

    /** A cached failure cannot survive a real pool change that makes the same child craftable. */
    @Test
    void failedNodeIsRetriedAfterAvailabilityChanges() {
        CraftPattern<String> bad = new CraftPattern<>(
                "target", 1, List.of(CraftInput.of("X", 1)), "bad");
        CraftPattern<String> good = new CraftPattern<>(
                "target", 1, List.of(CraftInput.of("producer", 1), CraftInput.of("X", 1)), "good");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(bad)
                .pattern(good)
                .pattern("X", 1, List.of(CraftInput.of("material", 1)))
                .pattern(new CraftPattern<>(
                        "producer",
                        1,
                        List.of(CraftInput.of("raw", 1)),
                        List.of(CraftOutput.of("material", 1)),
                        "producer"))
                .stock("raw", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "target", 1);

        assertTrue(plan.feasible(), "the byproduct changes X's exact availability state");
        assertEquals(1L, firingsOf(plan, good));
        assertEquals(0L, firingsOf(plan, bad));
        assertEquals(1L, plan.usedStock().get("raw"));
    }

    /**
     * A failure caused by the recursion guard is valid only at that depth. Reaching the same node from
     * a shorter alternative must retry it instead of reusing the deep-path failure proof.
     */
    @Test
    void failedNodeIsRetriedAtAShallowerDepth() {
        int prefixLength = CraftPlannerV2.MAX_OBTAIN_DEPTH - 2;
        CraftGraph.Builder<String> builder = CraftGraph.<String>builder()
                .pattern("target", 1, List.of(CraftInput.of("deep0", 1)))
                .pattern("target", 1, List.of(CraftInput.of("X", 1)))
                .pattern("X", 1, List.of(CraftInput.of("raw", 1)))
                .stock("raw", 1);
        for (int i = 0; i < prefixLength; i++) {
            String input = i + 1 == prefixLength ? "X" : "deep" + (i + 1);
            builder.pattern("deep" + i, 1, List.of(CraftInput.of(input, 1)));
        }

        CraftPlan<String> plan = CraftPlannerV2.plan(builder.build(), "target", 1);

        assertTrue(plan.feasible(), "the shallow target alternative must retry and craft X");
        assertEquals(1L, plan.usedStock().get("raw"));
    }

    /**
     * One hard-fuzzy AE2 pattern may contribute 64 concrete candidates. If all of them revisit the same
     * child and lose on a shared-stock conflict, that child's hot threshold may change route ordering
     * without killing the parent search or hiding any later ordinary alternative.
     */
    @Test
    void localCapFallsBackToGreedyWithoutKillingParentSearch() {
        int failedAlternatives = 64;
        CraftGraph.Builder<String> builder = CraftGraph.<String>builder()
                .pattern("X", 1, List.of(CraftInput.of("shared", 1)))
                .stock("shared", 1)
                .stock("good", 1);

        for (int i = 0; i < failedAlternatives; i++) {
            String sibling = "Y" + i;
            // A unique byproduct makes these branches materially distinct, ensuring this test
            // exercises the hot-node threshold rather than the equivalence proof below.
            builder.pattern(
                    sibling,
                    1,
                    List.of(CraftInput.of("shared", 1)),
                    List.of(CraftOutput.of("waste-" + i, 1)));
            builder.pattern(new CraftPattern<>(
                    "target",
                    1,
                    List.of(CraftInput.of("X", 1), CraftInput.of(sibling, 1)),
                    "bad-" + i));
        }
        CraftPattern<String> good = new CraftPattern<>(
                "target",
                1,
                List.of(CraftInput.of("X", 1), CraftInput.of("good", 1)),
                "good");
        builder.pattern(good);
        CraftGraph<String> graph = builder.build();

        CraftPlan<String> cap64 = CraftPlannerV2.plan(graph, "target", 1, 64);
        CraftPlan<String> defaultCap = CraftPlannerV2.plan(graph, "target", 1);

        assertTrue(cap64.feasible(), "the hot X subtree must continue searching");
        assertFalse(cap64.budgetExhausted(), "route reordering must not kill the whole plan");
        assertEquals(1L, firingsOf(cap64, good));
        assertTrue(defaultCap.feasible(), "the default must search beyond one complete fuzzy window");
        assertFalse(defaultCap.budgetExhausted());
        assertEquals(1L, firingsOf(defaultCap, good));
    }

    /**
     * A hot child may reach its visit threshold under many distinct parent alternatives. Its former
     * highest-capacity route can become impossible after a later parent consumes that route's leaf
     * stock, while another child route remains feasible. Capped mode must therefore choose against
     * the current rollback-restored pools instead of reusing one stale order for every future state.
     */
    @Test
    void hotChildReevaluatesGreedyRouteAgainstCurrentStock() {
        CraftPattern<String> xViaShared = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("shared", 1)), "X-via-shared");
        CraftPattern<String> xViaSpecial = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("special", 1)), "X-via-special");
        CraftGraph.Builder<String> builder = CraftGraph.<String>builder()
                .pattern(xViaShared)
                .stock("shared", 1)
                .stock("special", 1);
        List<String> blockedRouteStock = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String key = "blocked-route-" + i;
            blockedRouteStock.add(key);
            builder.pattern("X", 1, List.of(CraftInput.of(key, 1)));
            builder.stock(key, 1);
        }
        // This route deliberately sits behind several equal-capacity routes in immutable order.
        builder.pattern(xViaSpecial);

        for (int i = 0; i < CraftPlannerV2.DEFAULT_VISIT_CAP; i++) {
            String y = "Y" + i;
            String z = "Z" + i;
            builder.pattern(
                    y,
                    1,
                    List.of(CraftInput.of("shared", 1)),
                    List.of(CraftOutput.of("waste-" + i, 1)));
            builder.pattern(z, 1, List.of(CraftInput.of("special", 1)));
            List<CraftInput<String>> decoyInputs = new ArrayList<>();
            decoyInputs.add(CraftInput.of("X", 1));
            for (String key : blockedRouteStock) {
                decoyInputs.add(CraftInput.of(key, 1));
            }
            decoyInputs.add(CraftInput.of(y, 1));
            decoyInputs.add(CraftInput.of(z, 1));
            builder.pattern(new CraftPattern<>(
                    "target",
                    1,
                    decoyInputs,
                    List.of(CraftOutput.of("distinct-" + i, 1)),
                    "decoy-" + i));
        }
        List<CraftInput<String>> goodInputs = new ArrayList<>();
        goodInputs.add(CraftInput.of("shared", 1));
        for (String key : blockedRouteStock) {
            goodInputs.add(CraftInput.of(key, 1));
        }
        goodInputs.add(CraftInput.of("X", 1));
        CraftPattern<String> good = new CraftPattern<>(
                "target",
                1,
                goodInputs,
                "good");
        builder.pattern(good);

        CraftGraph<String> graph = builder.build();
        CraftPlan<String> plan = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> CraftPlannerV2.plan(graph, "target", 1));

        assertTrue(plan.feasible(),
                "after shared is reserved by the parent, hot X must route through special");
        assertEquals(1L, firingsOf(plan, good));
        assertEquals(0L, firingsOf(plan, xViaShared));
        assertEquals(1L, firingsOf(plan, xViaSpecial));
        assertEquals(1L, plan.usedStock().get("shared"));
        assertEquals(1L, plan.usedStock().get("special"));
    }

    /**
     * Four optimistic trap routes rank ahead of the only feasible fifth route. Each trap appears to
     * have capacity because its two child branches independently count the same token, but firing it
     * needs that token twice and fails. Repeated parent conflicts first make X hot; the final parent
     * is craftable only if hot-node search keeps going past all four traps.
     */
    @Test
    void hotNodeSearchDoesNotDropTheFifthFeasibleRoute() {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (int trap = 0; trap < 4; trap++) {
            String token = "token-" + trap;
            String left = "left-" + trap;
            String right = "right-" + trap;
            builder.pattern(left, 1, List.of(CraftInput.of(token, 1)));
            builder.pattern(right, 1, List.of(CraftInput.of(token, 1)));
            builder.pattern("X", 1, List.of(
                    CraftInput.of(left, 1),
                    CraftInput.of(right, 1)));
            builder.stock(token, 1);
        }

        CraftPattern<String> feasibleX = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("special", 1)), "X-via-special");
        builder.pattern(feasibleX).stock("special", 1);

        for (int parent = 0; parent < CraftPlannerV2.DEFAULT_VISIT_CAP; parent++) {
            String sibling = "sibling-" + parent;
            builder.pattern(sibling, 1, List.of(CraftInput.of("special", 1)));
            builder.pattern(new CraftPattern<>(
                    "target",
                    1,
                    List.of(CraftInput.of("X", 1), CraftInput.of(sibling, 1)),
                    List.of(CraftOutput.of("distinct-" + parent, 1)),
                    "decoy-" + parent));
        }
        CraftPattern<String> good = new CraftPattern<>(
                "target", 1, List.of(CraftInput.of("X", 1)), "good");
        builder.pattern(good);

        CraftPlan<String> plan = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> CraftPlannerV2.plan(builder.build(), "target", 1));

        assertTrue(plan.feasible(), "the fifth X route remains eligible while work budget remains");
        assertFalse(plan.budgetExhausted());
        assertEquals(1L, firingsOf(plan, feasibleX));
        assertEquals(1L, firingsOf(plan, good));
    }

    /** The exact balance model is independently bounded and does not consume route-search work. */
    @Test
    void globalIntegerFlowDoesNotSpendTinyRouteSearchBudget() {
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("X", 1, List.of(CraftInput.of("shared", 1)))
                .pattern("Y", 1, List.of(CraftInput.of("shared", 1)))
                .pattern("target", 1, List.of(
                        CraftInput.of("X", 1),
                        CraftInput.of("Y", 1)))
                .pattern("target", 1, List.of(CraftInput.of("good", 1)))
                .stock("shared", 1)
                .stock("good", 1)
                .build();

        PlanningResult<String> planning =
                CraftPlannerV2.planDetailed(graph, "target", 1, 1, 1);
        CraftPlan<String> plan = planning.plan();

        assertTrue(plan.feasible());
        assertFalse(plan.budgetExhausted());
        assertEquals(1L, plan.usedStock().get("good"));
        assertFalse(plan.usedStock().containsKey("shared"));
        assertTrue(plan.missing().isEmpty());
        assertEquals(1, planning.diagnostics().planRuns(),
                "the feasible integer vector must finish without a diagnostic replan");
        assertEquals(1, planning.diagnostics().compiledOrientations());
        assertEquals(1, planning.diagnostics().lowWidthSolved());
        assertEquals(0, planning.diagnostics().consumedSearchBudget());
        assertFalse(planning.diagnostics().searchCutoff());

        CraftGraph<String> replenished = CraftGraph.<String>builder()
                .pattern("X", 1, List.of(CraftInput.of("shared", 1)))
                .pattern("Y", 1, List.of(CraftInput.of("shared", 1)))
                .pattern("target", 1, List.of(
                        CraftInput.of("X", 1),
                        CraftInput.of("Y", 1)))
                .pattern("target", 1, List.of(CraftInput.of("good", 1)))
                .stock("shared", 2)
                .stock("good", 1)
                .build();
        CraftPlan<String> afterReplenishment =
                CraftPlannerV2.plan(replenished, "target", 1, 1, 1);

        assertTrue(afterReplenishment.feasible(),
                "the alternate shared-input route remains executable when fully stocked");
        assertFalse(afterReplenishment.budgetExhausted(),
                "a feasible integer-flow proof is complete even with a tiny search budget");
    }

    /**
     * X's first route is locally feasible but consumes the only shared unit before sibling Y asks for
     * it. The global integer model must choose X through special and preserve shared for Y without a
     * whole-plan deviation.
     */
    @Test
    void globalIntegerFlowResolvesLaterSiblingConflictWithoutReplay() {
        ReusableStockSource source = new ReusableStockSource("host", "pool");
        CraftPattern<String> xShared = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("shared", 1)), "X-shared");
        CraftPattern<String> xSpecial = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("special", 1)), "X-special");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(xShared)
                .pattern(xSpecial)
                .pattern("Y", 1, List.of(CraftInput.of("shared", 1)))
                .pattern("target", 1, List.of(
                        CraftInput.of("X", 1),
                        CraftInput.of("Y", 1),
                        CraftInput.returnedFrom("tool", 1, source)))
                .stock("shared", 1)
                .stock("special", 1)
                .reusableStock("host", "tool", 1)
                .reusableStockRoute(source, "tool", List.of("tool"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "target", 1);

        assertTrue(plan.feasible(), "one integer-flow solve should resolve the sibling conflict");
        assertFalse(plan.budgetExhausted());
        assertEquals(0L, firingsOf(plan, xShared));
        assertEquals(1L, firingsOf(plan, xSpecial));
        assertEquals(1L, plan.usedStock().get("shared"));
        assertEquals(1L, plan.usedStock().get("special"));
    }

    @Test
    void globalIntegerFlowFinishesWithinBudgetWithoutFalseCutoff() {
        ReusableStockSource source = new ReusableStockSource("host", "pool");
        CraftPattern<String> xShared = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("shared", 1)), "X-shared");
        CraftPattern<String> xSpecial = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("special", 1)), "X-special");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(xShared)
                .pattern(xSpecial)
                .pattern("Y", 1, List.of(CraftInput.of("shared", 1)))
                .pattern("target", 1, List.of(
                        CraftInput.of("X", 1),
                        CraftInput.of("Y", 1),
                        CraftInput.returnedFrom("tool", 1, source)))
                .stock("shared", 1)
                .stock("special", 1)
                .reusableStock("host", "tool", 1)
                .reusableStockRoute(source, "tool", List.of("tool"))
                .build();

        PlanningResult<String> planning =
                CraftPlannerV2.planDetailed(graph, "target", 1, 1, 20);
        CraftPlan<String> plan = planning.plan();

        assertTrue(plan.feasible(),
                "the selected integer vector must not depend on alternate-search budget");
        assertFalse(plan.budgetExhausted(),
                "the deterministic tail produced a complete executable proof");
        assertEquals(0L, firingsOf(plan, xShared));
        assertEquals(1L, firingsOf(plan, xSpecial));
        assertEquals(1L, plan.usedStock().get("shared"));
        assertEquals(1L, plan.usedStock().get("special"));
        assertTrue(plan.missing().isEmpty());
        assertFalse(planning.diagnostics().searchCutoff(),
                "successful ordered validation must not be reported as cut off");
        assertEquals(1, planning.diagnostics().compiledOrientations());
        assertEquals(0, planning.diagnostics().reusedCompilations(),
                "the integer vector must avoid whole-plan route replay");
        assertEquals(1, planning.diagnostics().lowWidthSolved());
        assertEquals(1, planning.diagnostics().planRuns());
        assertEquals(0, planning.diagnostics().consumedSearchBudget());
    }

    @Test
    void defaultAnytimeBudgetScalesAsReachableEdgesTimesLogEdges() {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        int depth = 256;
        for (int i = 0; i < depth; i++) {
            builder.pattern(
                    "N" + i,
                    1,
                    List.of(CraftInput.of("N" + (i + 1), 1)));
        }
        CraftGraph<String> graph = builder.build();

        int work = CraftPlannerV2.reachableWorkEstimate(graph, "N0");
        int budget = CraftPlannerV2.scaledSearchWorkBudget(graph, "N0");
        int log = 32 - Integer.numberOfLeadingZeros(work);
        long expected = Math.min(
                CraftPlannerV2.DEFAULT_SEARCH_WORK_BUDGET,
                Math.max(4_096L, (long) work * (log + 4L)));

        assertEquals(3 * depth + 1, work);
        assertEquals(expected, budget);
        assertTrue(budget >= work, "one complete reachable-graph pass must always fit");
    }

    /**
     * Complete material footprints differ (A versus a deep B chain), but both target routes have the
     * same unavoidable eight-C shortage. The proof is attached to that raw consumable requirement, so
     * the B subtree is never expanded merely to rediscover the already-visible C blocker.
     */
    @Test
    void commonConsumableShortfallPrunesDifferentMaterialRoutes() {
        CraftGraph.Builder<String> builder = CraftGraph.<String>builder()
                .pattern("target", 1, List.of(
                        CraftInput.of("A", 5),
                        CraftInput.of("C", 8)))
                .pattern("target", 1, List.of(
                        CraftInput.of("B0", 4),
                        CraftInput.of("C", 8)))
                .stock("A", 5);
        int depth = 48;
        for (int i = 0; i < depth; i++) {
            builder.pattern(
                    "B" + i,
                    1,
                    List.of(CraftInput.of("B" + (i + 1), 1)));
        }
        builder.stock("B" + depth, 4);

        PlanningResult<String> planning =
                CraftPlannerV2.planDetailed(builder.build(), "target", 1);
        CraftPlan<String> plan = planning.plan();

        assertFalse(plan.feasible());
        assertFalse(plan.budgetExhausted());
        assertEquals(8L, plan.missing().get("C"));
        assertEquals(1, planning.diagnostics().lowWidthInfeasible(),
                "the shared blocker is now proved by one global balance model");
        assertEquals(1, planning.diagnostics().planRuns());
    }

    @Test
    void consumableShortfallProofKeepsAFeasibleLowerQuantityRoute() {
        ReusableStockSource source = new ReusableStockSource("host", "pool");
        CraftPattern<String> needsEight = new CraftPattern<>(
                "target",
                1,
                List.of(
                        CraftInput.of("A", 1),
                        CraftInput.of("C", 8),
                        CraftInput.returnedFrom("tool", 1, source)),
                "needs-eight-C");
        CraftPattern<String> needsOne = new CraftPattern<>(
                "target",
                1,
                List.of(
                        CraftInput.of("B", 1),
                        CraftInput.of("C", 1),
                        CraftInput.returnedFrom("tool", 1, source)),
                "needs-one-C");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(needsEight)
                .pattern(needsOne)
                .stock("A", 1)
                .stock("B", 1)
                .stock("C", 1)
                .reusableStock("host", "tool", 1)
                .reusableStockRoute(source, "tool", List.of("tool"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "target", 1);

        assertTrue(plan.feasible());
        assertEquals(0L, firingsOf(plan, needsEight));
        assertEquals(1L, firingsOf(plan, needsOne));
        assertEquals(1L, plan.usedStock().get("C"));
    }

    @Test
    void consumableShortfallProofIsScopedToTheAvailabilityState() {
        ReusableStockSource source = new ReusableStockSource("host", "pool");
        CraftPattern<String> xViaA = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("A", 1), CraftInput.of("C", 8)), "X-via-A");
        CraftPattern<String> xViaB = new CraftPattern<>(
                "X", 1, List.of(CraftInput.of("B", 1), CraftInput.of("C", 8)), "X-via-B");
        CraftPattern<String> makeC = new CraftPattern<>(
                "P",
                1,
                List.of(CraftInput.of("seed", 1)),
                List.of(CraftOutput.of("C", 8)),
                "make-C");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(xViaA)
                .pattern(xViaB)
                .pattern(makeC)
                .pattern("target", 1, List.of(
                        CraftInput.of("X", 1),
                        CraftInput.returnedFrom("tool", 1, source)))
                .pattern("target", 1, List.of(
                        CraftInput.of("P", 1),
                        CraftInput.of("X", 1),
                        CraftInput.returnedFrom("tool", 1, source)))
                .stock("A", 1)
                .stock("B", 1)
                .stock("seed", 1)
                .reusableStock("host", "tool", 1)
                .reusableStockRoute(source, "tool", List.of("tool"))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "target", 1);

        assertTrue(plan.feasible(),
                "a proof learned before C is produced must not survive the availability change");
        assertEquals(1L, firingsOf(plan, makeC));
        assertEquals(1L, plan.usedStock().get("seed"));
        assertEquals(0L, plan.missing().getOrDefault("C", 0L));
    }

    /**
     * B accepts two concrete fuzzy variants. Their intermediate names differ, but both normalize to
     * exactly 1 E per B, so one failed material tree proves the other equivalent for this rollback
     * state. The amount is deliberately large to verify that proof work remains quantity-independent.
     */
    @Test
    void equivalentMaterialBranchesAreSearchedOnce() {
        CraftPattern<String> viaA1 = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A1", 1)), "B-via-A1");
        CraftPattern<String> viaA2 = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A2", 1)), "B-via-A2");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(viaA1)
                .pattern(viaA2)
                .pattern("A1", 1, List.of(CraftInput.of("C", 1)))
                .pattern("A2", 1, List.of(CraftInput.of("D", 1)))
                .pattern("C", 1, List.of(CraftInput.of("E", 1)))
                .pattern("D", 1, List.of(CraftInput.of("E", 1)))
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "B", 1_000);

        assertFalse(plan.feasible());
        assertEquals(1_000L, plan.missing().get("E"));
        assertEquals(1_000L, firingsOf(plan, viaA1));
        assertEquals(0L, firingsOf(plan, viaA2));
        assertEquals(3, plan.itemsProcessed(),
                "the only material class is committed once without speculative re-expansion");
    }

    /** Sixty-four equivalent fuzzy branches still expand one proof tree, independent of request size. */
    @Test
    void manyEquivalentBranchesStayConstantInRequestAmount() {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        List<CraftPattern<String>> alternatives = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CraftPattern<String> alternative = new CraftPattern<>(
                    "B", 1, List.of(CraftInput.of("A" + i, 1)), "B-via-A" + i);
            alternatives.add(alternative);
            builder.pattern(alternative);
            builder.pattern("A" + i, 1, List.of(CraftInput.of("C" + i, 1)));
            builder.pattern("C" + i, 1, List.of(CraftInput.of("E", 1)));
        }
        CraftGraph<String> graph = builder.build();

        CraftPlan<String> one = CraftPlannerV2.plan(graph, "B", 1);
        CraftPlan<String> billion = CraftPlannerV2.plan(graph, "B", 1_000_000_000L);

        assertFalse(one.feasible());
        assertFalse(billion.feasible());
        assertEquals(3, one.itemsProcessed());
        assertEquals(one.itemsProcessed(), billion.itemsProcessed(),
                "request magnitude must not multiply equivalent branch expansion");
        assertEquals(1_000_000_000L, billion.missing().get("E"));
        assertEquals(1_000_000_000L, firingsOf(billion, alternatives.get(0)));
        for (int i = 1; i < alternatives.size(); i++) {
            assertEquals(0L, firingsOf(billion, alternatives.get(i)));
        }
    }

    /** Different terminal quantities are not equivalent and must both be searched. */
    @Test
    void differentMaterialRatiosAreNotPruned() {
        CraftPattern<String> viaA1 = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A1", 1)), "B-via-A1");
        CraftPattern<String> viaA2 = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A2", 1)), "B-via-A2");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(viaA1)
                .pattern(viaA2)
                .pattern("A1", 1, List.of(CraftInput.of("C", 1)))
                .pattern("A2", 1, List.of(CraftInput.of("D", 1)))
                .pattern("C", 1, List.of(CraftInput.of("E", 1)))
                .pattern("D", 1, List.of(CraftInput.of("E", 2)))
                .build();

        PlanningResult<String> planning = CraftPlannerV2.planDetailed(graph, "B", 1_000);
        CraftPlan<String> plan = planning.plan();

        assertFalse(plan.feasible());
        assertEquals(1, planning.diagnostics().lowWidthInfeasible(),
                "both material ratios must participate in the global infeasibility proof");
        assertEquals(1, planning.diagnostics().planRuns());
    }

    /**
     * v2-memo-deps: a shared deep chain feeds many parents. The linear backbone resolves each item
     * exactly once, so work is O(parents + depth), NOT parents*depth (which is what re-expanding the
     * shared subgraph per parent would cost).
     */
    @Test
    void sharedSubgraphIsResolvedOncePerItem() {
        int parents = 50;
        int depth = 50;
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();

        java.util.List<CraftInput<String>> tInputs = new java.util.ArrayList<>();
        for (int i = 0; i < parents; i++) {
            tInputs.add(CraftInput.of("P" + i, 1));
            b.pattern("P" + i, 1, List.of(CraftInput.of("common", 1)));
        }
        b.pattern(new CraftPattern<>("T", 1, tInputs, "T"));
        b.pattern("common", 1, List.of(CraftInput.of("c0", 1)));
        for (int i = 0; i < depth; i++) {
            b.pattern("c" + i, 1, List.of(CraftInput.of("c" + (i + 1), 1)));
        }
        b.stock("c" + depth, 1_000);

        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "T", 1);

        assertTrue(plan.feasible());
        assertTrue(plan.itemsProcessed() <= parents + depth + 10,
                "shared chain must be visited once, not per-parent; got " + plan.itemsProcessed());
    }

    /**
     * v2-lazy-deduct: a single linear pass splits demand across two recipes by current remaining
     * capacity (reservation shrinks capRemaining with O(1) deduction), draining the scarcer input
     * only as far as it goes and routing the rest to the other — no backtracking needed.
     */
    @Test
    void linearPassSplitsAcrossRecipesByRemainingCapacity() {
        CraftPattern<String> viaY = new CraftPattern<>("A", 1, List.of(CraftInput.of("y", 1)), "viaY");
        CraftPattern<String> viaX = new CraftPattern<>("A", 1, List.of(CraftInput.of("x", 1)), "viaX");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(viaY)
                .pattern(viaX)
                .stock("y", 6)
                .stock("x", 4)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "A", 10);

        assertTrue(plan.feasible());
        assertEquals(6L, firingsOf(plan, viaY));
        assertEquals(4L, firingsOf(plan, viaX));
        assertEquals(6L, plan.usedStock().get("y"));
        assertEquals(4L, plan.usedStock().get("x"));
        assertTrue(plan.missing().isEmpty());
    }

    /**
     * One fuzzy B input expands to the concrete A1/A2 producer routes. Both routes share E, so a
     * greedy all-A2 allocation makes only three B; the exact feasible split is 3 x A1 plus 1 x A2.
     */
    @Test
    void linearPassSplitsFuzzyProducerRoutesAroundSharedStock() {
        CraftPattern<String> bViaA1 = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A1", 1)), "B-via-A1");
        CraftPattern<String> bViaA2 = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A2", 1)), "B-via-A2");
        CraftPattern<String> makeA1 = new CraftPattern<>(
                "A1", 1, List.of(CraftInput.of("C", 1), CraftInput.of("E", 1)), "make-A1");
        CraftPattern<String> makeA2 = new CraftPattern<>(
                "A2", 1, List.of(CraftInput.of("D", 1), CraftInput.of("E", 2)), "make-A2");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(bViaA1)
                .pattern(bViaA2)
                .pattern(makeA1)
                .pattern(makeA2)
                .stock("C", 3)
                .stock("D", 4)
                .stock("E", 5)
                .build();

        PlanningResult<String> planning = CraftPlannerV2.planDetailed(graph, "B", 4);
        CraftPlan<String> plan = planning.plan();

        assertTrue(plan.feasible());
        assertEquals(3L, firingsOf(plan, bViaA1));
        assertEquals(1L, firingsOf(plan, bViaA2));
        assertEquals(3L, firingsOf(plan, makeA1));
        assertEquals(1L, firingsOf(plan, makeA2));
        assertEquals(3L, plan.usedStock().get("C"));
        assertEquals(1L, plan.usedStock().get("D"));
        assertEquals(5L, plan.usedStock().get("E"));
        assertEquals(0, planning.diagnostics().consumedSearchBudget(),
                "the capacity split is solved by the deterministic linear allocation pass");
        assertTrue(plan.missing().isEmpty());
    }

    /**
     * Property test: thousands of randomly generated multi-layer DAGs with random output/input ratios,
     * random stock and random byproducts. Each plan is checked by an INDEPENDENT mass-balance oracle
     * (re-derived from the reported firings + usedStock, never from planner internals):
     * <ul>
     *   <li>no stock is ever over-drawn ({@code usedStock[k] <= stock[k]});</li>
     *   <li>flow conservation holds at every item: {@code inflow + missing >= outflow}. On a DAG this
     *       is exactly the condition for the firing schedule to be executable, so a {@code feasible}
     *       plan that passes this is genuinely valid (no false positives) and a {@code missing} report
     *       is numerically consistent.</li>
     * </ul>
     */
    @Test
    void randomNestedGraphsAreSoundUnderMassBalance() {
        for (long seed = 0; seed < 3_000; seed++) {
            RandomGraph rg = buildRandomGraph(seed);
            CraftPlan<String> plan = CraftPlannerV2.plan(rg.graph, rg.target, rg.amount);

            assertTrue(plan.supported(), "acyclic graph must be supported, seed=" + seed);
            assertMassBalance(plan, rg, seed);
        }
    }

    private static void assertMassBalance(CraftPlan<String> plan, RandomGraph rg, long seed) {
        Map<String, Long> inflow = new HashMap<>();
        Map<String, Long> outflow = new HashMap<>();

        for (Map.Entry<String, Long> e : plan.usedStock().entrySet()) {
            long stock = rg.stock.getOrDefault(e.getKey(), 0L);
            assertTrue(e.getValue() <= stock,
                    "over-drew stock " + e.getKey() + " used=" + e.getValue() + " have=" + stock + " seed=" + seed);
            inflow.merge(e.getKey(), e.getValue(), Long::sum);
        }
        for (CraftPattern<String> p : rg.patterns) {
            long f = plan.firings().getOrDefault(p, 0L);
            if (f <= 0) {
                continue;
            }
            inflow.merge(p.output(), f * p.outputAmount(), Long::sum);
            for (CraftOutput<String> out : p.byproducts()) {
                inflow.merge(out.key(), f * out.amount(), Long::sum);
            }
            for (CraftInput<String> in : p.inputs()) {
                if (!in.returned()) {
                    outflow.merge(in.key(), f * in.amount(), Long::sum);
                }
            }
        }
        outflow.merge(rg.target, rg.amount, Long::sum);

        Set<String> keys = new LinkedHashSet<>(inflow.keySet());
        keys.addAll(outflow.keySet());
        for (String k : keys) {
            long supply = inflow.getOrDefault(k, 0L) + plan.missing().getOrDefault(k, 0L);
            long demand = outflow.getOrDefault(k, 0L);
            assertTrue(supply >= demand,
                    "conservation broken for " + k + ": inflow+missing=" + supply + " < outflow=" + demand
                            + " (feasible=" + plan.feasible() + ", seed=" + seed + ")");
        }
        if (plan.feasible()) {
            assertTrue(plan.missing().isEmpty(), "feasible plan must report no missing, seed=" + seed);
        }
    }

    private record RandomGraph(CraftGraph<String> graph, List<CraftPattern<String>> patterns,
                               Map<String, Long> stock, String target, long amount) {
    }

    /**
     * Builds a random acyclic crafting graph: item {@code i} may only consume items {@code j > i}, so
     * the graph is a DAG by construction (no recursion). Amounts are kept small so the multiplicative
     * demand along chains stays comfortably within {@code long} for the oracle's exact arithmetic.
     */
    private static RandomGraph buildRandomGraph(long seed) {
        Random rnd = new Random(seed);
        int n = 8 + rnd.nextInt(7); // 8..14 items
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        List<CraftPattern<String>> patterns = new ArrayList<>();
        Map<String, Long> stock = new HashMap<>();

        for (int i = 0; i < n; i++) {
            String ki = "i" + i;
            boolean leaf = i >= n - 2 || (i > 0 && rnd.nextInt(100) < 30);
            if (leaf) {
                long s = rnd.nextInt(40); // may be 0 -> contributes to an infeasible branch
                if (s > 0) {
                    stock.put(ki, s);
                    b.stock(ki, s);
                }
                continue;
            }
            if (rnd.nextInt(100) < 20) { // a craftable item that also has some stock
                long s = 1 + rnd.nextInt(8);
                stock.merge(ki, s, Long::sum);
                b.stock(ki, s);
            }
            int recipes = 1 + rnd.nextInt(2); // 1..2 competing recipes
            for (int r = 0; r < recipes; r++) {
                int outAmt = 1 + rnd.nextInt(3);
                int numIn = 1 + rnd.nextInt(3);
                Set<String> chosen = new LinkedHashSet<>();
                List<CraftInput<String>> inputs = new ArrayList<>();
                for (int c = 0; c < numIn; c++) {
                    int j = i + 1 + rnd.nextInt(n - i - 1);
                    if (chosen.add("i" + j)) {
                        inputs.add(CraftInput.of("i" + j, 1 + rnd.nextInt(3)));
                    }
                }
                List<CraftOutput<String>> byp = new ArrayList<>();
                if (rnd.nextInt(100) < 25 && i + 1 < n) {
                    int j = i + 1 + rnd.nextInt(n - i - 1);
                    byp.add(CraftOutput.of("i" + j, 1 + rnd.nextInt(2)));
                }
                CraftPattern<String> p = new CraftPattern<>(ki, outAmt, inputs, byp, "p" + i + "_" + r);
                patterns.add(p);
                b.pattern(p);
            }
        }
        long amount = 1 + rnd.nextInt(4);
        return new RandomGraph(b.build(), patterns, stock, "i0", amount);
    }

    /**
     * Completeness via reverse construction: build a graph that is feasible <em>by construction</em>
     * (a witness plan exists with exactly-provisioned stock), then add real <em>trap</em> recipes that
     * the optimistic capacity estimate prefers but that are actually infeasible (a diamond that
     * double-counts one shared unit). The greedy linear pass takes the bait, fails, and the bounded
     * budgeted search must roll back and recover the witness.
     *
     * <p>The planner MUST come back feasible across thousands of random graphs and complete within the
     * global work budget.
     */
    @Test
    void reverseConstructedGraphsAreAlwaysCraftable() {
        for (long seed = 0; seed < 3_000; seed++) {
            RandomGraph rg = buildFeasibleGraph(seed);
            CraftPlan<String> plan = CraftPlannerV2.plan(rg.graph, rg.target, rg.amount);

            assertTrue(plan.supported(), "seed=" + seed);
            assertTrue(plan.feasible(),
                    "reverse-constructed graph must be craftable but planner reported missing="
                            + plan.missing() + " seed=" + seed);
            assertTrue(plan.missing().isEmpty(), "seed=" + seed);
            assertFalse(plan.budgetExhausted(),
                    "constructed witness should fit within the work budget, seed=" + seed);
            assertMassBalance(plan, rg, seed); // soundness on top of completeness
        }
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static RandomGraph buildFeasibleGraph(long seed) {
        Random rnd = new Random(seed);
        int m = 6 + rnd.nextInt(8); // 6..13 witness items
        long amount = 1 + rnd.nextInt(4);

        boolean[] leaf = new boolean[m];
        List<CraftPattern<String>> witness = new ArrayList<>();
        CraftPattern<?>[] witnessOf = new CraftPattern<?>[m];
        // 1) decide structure + witness recipe per craftable item (inputs strictly deeper => DAG).
        for (int i = 0; i < m; i++) {
            leaf[i] = i >= m - 2 || (i > 0 && rnd.nextInt(100) < 25);
            if (leaf[i]) {
                continue;
            }
            int outAmt = 1 + rnd.nextInt(3);
            int numIn = 1 + rnd.nextInt(3);
            Set<String> chosen = new LinkedHashSet<>();
            List<CraftInput<String>> inputs = new ArrayList<>();
            for (int c = 0; c < numIn; c++) {
                int j = i + 1 + rnd.nextInt(m - i - 1);
                if (chosen.add("w" + j)) {
                    inputs.add(CraftInput.of("w" + j, 1 + rnd.nextInt(3)));
                }
            }
            List<CraftOutput<String>> byp = new ArrayList<>();
            if (rnd.nextInt(100) < 30) {
                // This test promises a DAG witness. An unused bonus still exercises byproduct mass
                // balance without accidentally feeding a dependency back into the witness graph.
                byp.add(CraftOutput.of("bonus" + i, 1 + rnd.nextInt(2)));
            }
            CraftPattern<String> p = new CraftPattern<>("w" + i, outAmt, inputs, byp, "w" + i);
            witnessOf[i] = p;
            witness.add(p);
        }

        // 2) propagate demand top-down (index order is topological) to size the leaf stock exactly.
        long[] required = new long[m];
        required[0] = amount;
        for (int i = 0; i < m; i++) {
            if (required[i] <= 0 || leaf[i]) {
                continue;
            }
            @SuppressWarnings("unchecked")
            CraftPattern<String> p = (CraftPattern<String>) witnessOf[i];
            long firings = ceilDiv(required[i], p.outputAmount());
            for (CraftInput<String> in : p.inputs()) {
                int j = Integer.parseInt(in.key().substring(1));
                required[j] += firings * in.amount();
            }
        }

        // 3) assemble the graph: witness recipes + exactly-enough leaf stock.
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        List<CraftPattern<String>> all = new ArrayList<>(witness);
        Map<String, Long> stock = new HashMap<>();
        for (CraftPattern<String> p : witness) {
            b.pattern(p);
        }
        for (int i = 0; i < m; i++) {
            if (leaf[i] && required[i] > 0) {
                stock.put("w" + i, required[i]);
                b.stock("w" + i, required[i]);
            }
        }

        // 4) traps: on demand-path items, add a recipe the optimistic capacity *prefers* (it can
        //    "make" more than the witness) but that is actually infeasible — a self-contained diamond
        //    t1 <- s and t2 <- s sharing a single unit s, so making both t1 AND t2 is impossible. The
        //    greedy pass commits the trap, fails, and the bounded search must backtrack to the witness
        //    (whose dedicated stock is restored on rollback). Provably recoverable, in 2 visits/node.
        for (int i = 0; i < m; i++) {
            if (leaf[i] || required[i] <= 0 || rnd.nextInt(100) >= 50) {
                continue;
            }
            String s = "s" + i;
            String t1 = "t1_" + i;
            String t2 = "t2_" + i;
            b.stock(s, 1);
            stock.put(s, 1L);
            CraftPattern<String> tp1 = new CraftPattern<>(t1, 1, List.of(CraftInput.of(s, 1)), "tp1_" + i);
            CraftPattern<String> tp2 = new CraftPattern<>(t2, 1, List.of(CraftInput.of(s, 1)), "tp2_" + i);
            // outputAmount > required[i] => trap's optimistic capacity beats the witness, so it's tried first.
            CraftPattern<String> trap = new CraftPattern<>(
                    "w" + i, required[i] + 1, List.of(CraftInput.of(t1, 1), CraftInput.of(t2, 1)), "trap" + i);
            all.add(tp1);
            all.add(tp2);
            all.add(trap);
            b.pattern(tp1);
            b.pattern(tp2);
            b.pattern(trap);
        }

        return new RandomGraph(b.build(), all, stock, "w0", amount);
    }

    /**
     * A recipe chain far deeper than {@link CraftPlannerV2#MAX_OBTAIN_DEPTH} (and any thread stack) that
     * is feasible and non-contended must be resolved by the iterative linear backbone — never recursing,
     * never exhausting the budget, never overflowing.
     */
    @Test
    void deepFeasibleChainResolvedIterativelyWithoutOverflow() {
        int n = 5_000;
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        for (int i = 0; i < n; i++) {
            b.pattern("A" + i, 1, List.of(CraftInput.of("A" + (i + 1), 1)));
        }
        b.stock("A" + n, 10);

        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "A0", 3);

        assertTrue(plan.feasible(), "deep non-contended chain solved by the iterative linear pass");
        assertEquals(3L, plan.usedStock().get("A" + n));
        assertTrue(plan.missing().isEmpty());
        assertFalse(plan.budgetExhausted(), "no recursion, so no depth/visit degradation");
    }

    /**
     * Ordinary unchanged catalysts do not make a deep acyclic graph seed-order-sensitive. The shared
     * seed reserve stays linear and the graph must not fall into the recursive depth guard.
     */
    @Test
    void deepAcyclicCatalystChainRemainsLinear() {
        int n = 1_000;
        CraftGraph.Builder<String> builder = CraftGraph.<String>builder()
                .stock("A" + n, 1)
                .stock("template", 1);
        for (int i = 0; i < n; i++) {
            builder.pattern("A" + i, 1, List.of(
                    CraftInput.of("A" + (i + 1), 1),
                    CraftInput.returned("template", 1)));
        }

        CraftPlan<String> plan = CraftPlannerV2.plan(builder.build(), "A0", 1);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("A" + n));
        assertEquals(1L, plan.usedStock().get("template"));
        assertFalse(plan.budgetExhausted());
    }

    /**
     * The same chain with no stock anywhere forces the recursive bounded search (the linear pass reports
     * infeasible). Without {@link CraftPlannerV2#MAX_OBTAIN_DEPTH} the descent would recurse {@code n}
     * deep and {@code StackOverflowError}; with it, only the over-deep branch degrades to a
     * best-effort "missing" result instead of crashing or poisoning the whole calculation.
     */
    @Test
    void deepRecursiveChainDegradesInsteadOfOverflowing() {
        int n = 5_000;
        CraftGraph.Builder<String> b = CraftGraph.<String>builder();
        for (int i = 0; i < n; i++) {
            b.pattern("A" + i, 1, List.of(CraftInput.of("A" + (i + 1), 1)));
        }
        // No stock: linearPass is infeasible -> escalates to obtain()'s recursive descent.

        CraftPlan<String> plan = CraftPlannerV2.plan(b.build(), "A0", 1);

        assertTrue(plan.supported());
        assertFalse(plan.feasible());
        assertFalse(plan.missing().isEmpty(), "shortfall reported, not crashed");
        assertFalse(plan.budgetExhausted(), "depth degradation is branch-local, not a global result flag");
    }

    /**
     * A reusable-seed output can be revisited by every materially distinct parent branch. Capacity
     * ranking depends only on the immutable graph snapshot and must therefore be computed once, not
     * re-sorted through {@code producibleVia} before every node-budget check.
     */
    @Test
    void feedbackCapacityRankingIsCachedAcrossParentRetries() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var source = new ReusableStockSource("host", "loop");
            CraftGraph.Builder<String> builder = CraftGraph.<String>builder()
                    .pattern("A", 1, List.of(CraftInput.returnedFrom("B", 1, source)))
                    .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                    .reusableStock("host", "A", 1)
                    .reusableStockRoute(source, "B", List.of("B"));

            for (int route = 0; route < 64; route++) {
                builder.pattern("A", 1, List.of(CraftInput.of("raw-" + route, 1)));
            }
            for (int parent = 0; parent < 1_024; parent++) {
                builder.pattern(new CraftPattern<>(
                        "target",
                        1,
                        List.of(CraftInput.of("A", 1), CraftInput.of("missing-" + parent, 1)),
                        List.of(CraftOutput.of("distinct-" + parent, 1)),
                        "parent-" + parent));
            }

            CraftPlan<String> plan = CraftPlannerV2.plan(builder.build(), "target", 1);
            assertFalse(plan.feasible());
            assertFalse(plan.missing().isEmpty());
        });
    }

    @Test
    void fuzzySiblingRouteConsumesStockBeforeCraftingTheDeclaredVariant() {
        Object fuzzyPattern = new Object();
        CraftPattern<String> fromStockVariant = new CraftPattern<>(
                "tier3", 1, List.of(CraftInput.of("tier2-with-other-nbt", 1)), fuzzyPattern);
        CraftPattern<String> fromDeclaredVariant = new CraftPattern<>(
                "tier3", 1, List.of(CraftInput.of("tier2-declared-nbt", 1)), fuzzyPattern);
        CraftPattern<String> makeDeclaredVariant = new CraftPattern<>(
                "tier2-declared-nbt", 1, List.of(CraftInput.of("tier1", 1)), "tier1-to-tier2");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(fromStockVariant)
                .pattern(fromDeclaredVariant)
                .pattern(makeDeclaredVariant)
                .stock("tier2-with-other-nbt", 1)
                .stock("tier1", 64)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "tier3", 1);

        assertTrue(plan.feasible());
        assertEquals(1L, firingsOf(plan, fromStockVariant));
        assertEquals(0L, firingsOf(plan, fromDeclaredVariant));
        assertEquals(0L, firingsOf(plan, makeDeclaredVariant));
        assertEquals(1L, plan.usedStock().get("tier2-with-other-nbt"));
        assertNull(plan.usedStock().get("tier1"));
    }

    @Test
    void fuzzySiblingRouteCraftsOnlyTheAmountMissingAfterStock() {
        Object fuzzyPattern = new Object();
        CraftPattern<String> fromStockVariant = new CraftPattern<>(
                "tier3", 1, List.of(CraftInput.of("tier2-with-other-nbt", 1)), fuzzyPattern);
        CraftPattern<String> fromDeclaredVariant = new CraftPattern<>(
                "tier3", 1, List.of(CraftInput.of("tier2-declared-nbt", 1)), fuzzyPattern);
        CraftPattern<String> makeDeclaredVariant = new CraftPattern<>(
                "tier2-declared-nbt", 1, List.of(CraftInput.of("tier1", 1)), "tier1-to-tier2");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(fromStockVariant)
                .pattern(fromDeclaredVariant)
                .pattern(makeDeclaredVariant)
                .stock("tier2-with-other-nbt", 1)
                .stock("tier1", 64)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "tier3", 2);

        assertTrue(plan.feasible());
        assertEquals(1L, firingsOf(plan, fromStockVariant));
        assertEquals(1L, firingsOf(plan, fromDeclaredVariant));
        assertEquals(1L, firingsOf(plan, makeDeclaredVariant));
        assertEquals(1L, plan.usedStock().get("tier2-with-other-nbt"));
        assertEquals(1L, plan.usedStock().get("tier1"));
    }

    @Test
    void balancedSelfReturnUsesOneBootstrapItemAtBillionScale() {
        long amount = 1_000_000_000L;
        CraftPattern<String> pattern = new CraftPattern<>(
                "B", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("C", 1)),
                List.of(CraftOutput.of("C", 1)),
                "1A+1C_to_1B+1C");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(pattern)
                .stock("A", amount)
                .stock("C", 1)
                .build();

        CraftPlan<String> plan = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> CraftPlannerV2.plan(graph, "B", amount));

        assertTrue(plan.feasible(), () -> "missing=" + plan.missing());
        assertEquals(amount, firingsOf(plan, pattern));
        assertEquals(amount, plan.usedStock().get("A"));
        assertEquals(1L, plan.usedStock().get("C"));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void balancedSelfReturnCannotBootstrapFromItsOwnOutput() {
        CraftPattern<String> pattern = new CraftPattern<>(
                "B", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("C", 1)),
                List.of(CraftOutput.of("C", 1)),
                "1A+1C_to_1B+1C");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(pattern)
                .stock("A", 1_000)
                .build();

        CraftPlan<String> plan = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> CraftPlannerV2.plan(graph, "B", 1_000));

        assertFalse(plan.feasible());
        assertEquals(Map.of("C", 1L), plan.missing());
    }

    @Test
    void multiStepContainerCycleCraftsOneBootstrapState() {
        CraftPattern<String> finish = new CraftPattern<>(
                "result", 1, List.of(CraftInput.consumedReturning("full", 1, "empty")),
                List.of(CraftOutput.of("empty", 1)), "finish");
        CraftPattern<String> fill = new CraftPattern<>(
                "full", 1, List.of(CraftInput.of("dusted", 1)), "fill");
        CraftPattern<String> dust = new CraftPattern<>(
                "dusted", 1, List.of(CraftInput.of("empty", 1), CraftInput.of("dust", 2)), "dust");
        CraftPattern<String> makeEmpty = new CraftPattern<>(
                "empty", 1, List.of(CraftInput.of("gem", 3)), "makeEmpty");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(finish)
                .pattern(fill)
                .pattern(dust)
                .pattern(makeEmpty)
                .stock("dust", 20)
                .stock("gem", 3)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 10);

        assertTrue(plan.feasible(), plan.toString());
        assertEquals(10L, firingsOf(plan, finish));
        assertEquals(10L, firingsOf(plan, fill));
        assertEquals(10L, firingsOf(plan, dust));
        assertEquals(1L, firingsOf(plan, makeEmpty));
        assertEquals(20L, plan.usedStock().get("dust"));
        assertEquals(3L, plan.usedStock().get("gem"));
        assertTrue(plan.missing().isEmpty());
    }
}
