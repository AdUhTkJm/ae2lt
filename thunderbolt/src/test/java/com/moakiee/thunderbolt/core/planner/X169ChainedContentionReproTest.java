package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * "串一下" regression: a contended node chained in series with deep uncontended Fibonacci towers.
 *
 * <p>Per-edge recursion multiplies obtain() calls by the number of root-to-node paths, so merely
 * <em>evaluating</em> one speculative route on top of a shared-node tower of depth {@code n} used
 * to cost {@code O(2^n)} search budget. The planner must instead aggregate demand through
 * uncontended regions and spend its search budget only on the contended nodes.
 */
class X169ChainedContentionReproTest {

    /** Adds a Fibonacci tower: {@code prefix_i = prefix_(i-1) + prefix_(i-2)}, tiers 2..depth-1. */
    private static void fibTower(CraftGraph.Builder<String> b, String prefix, String base0, String base1, int depth) {
        String prev2 = base0;
        String prev1 = base1;
        for (int i = 2; i < depth; i++) {
            String cur = prefix + i;
            b.pattern(cur, 1, List.of(CraftInput.of(prev1, 1), CraftInput.of(prev2, 1)));
            prev2 = prev1;
            prev1 = cur;
        }
    }

    private static String top(String prefix, int depth) {
        return prefix + (depth - 1);
    }

    /**
     * The boundedBacktrackRecoversAlternative shape with a 30-deep Fibonacci tower spliced between
     * the contended fork and the shared resource. Route r1 (B + D, both towers over one shared
     * pool) is infeasible at 3x; the planner must fully evaluate it, roll it back, and commit the
     * iron route — which only works if evaluating a tower branch is cheap.
     */
    @Test
    void backtrackAcrossDeepUncontendedTower() {
        int depth = 30;
        long fibTop = fib(depth); // shared units needed per tower top ~ fib(30) = 832040
        CraftGraph.Builder<String> b = CraftGraph.builder();
        fibTower(b, "bt", "shared", "shared", depth);
        fibTower(b, "dt", "shared", "shared", depth);
        b.pattern("B", 1, List.of(CraftInput.of(top("bt", depth), 1)));
        b.pattern("D", 1, List.of(CraftInput.of(top("dt", depth), 1)));
        CraftPattern<String> r1 = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 1), CraftInput.of("D", 1)), "r1");
        CraftPattern<String> r2 = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("iron", 1)), "r2");
        b.pattern(r1).pattern(r2);
        // Enough shared for ~1.4 A via r1 (each A needs 2*fibTop shared), so 3 A must use iron.
        b.stock("shared", 3 * fibTop);
        b.stock("iron", 3);

        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(b.build(), "A", 3);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[chained] 3x A: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " budgetExhausted=" + r.plan().budgetExhausted()
                + " wallMs=" + ms);
        System.out.println("[chained] diagnostics=" + r.diagnostics());
        assertTrue(r.plan().feasible(),
                "search must evaluate the tower route cheaply and recover via iron, got missing="
                        + r.plan().missing());
        assertEquals(3L, r.plan().usedStock().get("iron"));
    }

    /**
     * Series composition: an uncontended Fibonacci tower stacked ON TOP of a contended two-route
     * region (each mid tier has a cheap-but-limited and an expensive route). The top tower's demand
     * must arrive at the contended region aggregated, not once per path.
     */
    @Test
    void uncontendedTowerAboveContendedRegion() {
        int depth = 32;
        CraftGraph.Builder<String> b = CraftGraph.builder();
        // Contended bottom: mid has two routes over different raws.
        b.pattern(new CraftPattern<>("mid", 1, List.of(CraftInput.of("cheap", 1)), "viaCheap"));
        b.pattern(new CraftPattern<>("mid", 1, List.of(CraftInput.of("costly", 2)), "viaCostly"));
        // Uncontended Fibonacci tower on top of mid.
        fibTower(b, "up", "mid", "mid", depth);
        long fibTop = 2 * fib(depth); // mid demand for 1 top
        b.stock("cheap", fibTop / 3);          // cheap covers only a third
        b.stock("costly", 2 * fibTop * 2);     // costly covers the rest
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(
                b.build(), top("up", depth), 1);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[chained] 1x tower-over-contention: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " budgetExhausted=" + r.plan().budgetExhausted()
                + " wallMs=" + ms);
        System.out.println("[chained] diagnostics=" + r.diagnostics());
        assertTrue(r.plan().feasible(),
                "aggregated demand must reach the contended region once, got missing="
                        + r.plan().missing());
    }

    /** Starved series chain must still expand missing all the way to raw leaves. */
    @Test
    void starvedSeriesChainReportsLeafMissing() {
        int depth = 40;
        CraftGraph.Builder<String> b = CraftGraph.builder();
        b.pattern(new CraftPattern<>("mid", 1, List.of(CraftInput.of("cheap", 1)), "viaCheap"));
        b.pattern(new CraftPattern<>("mid", 1, List.of(CraftInput.of("costly", 2)), "viaCostly"));
        fibTower(b, "up", "mid", "mid", depth);
        b.stock("cheap", 10);
        b.stock("costly", 10);
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(
                b.build(), top("up", depth), 1);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[chained] starved series: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " budgetExhausted=" + r.plan().budgetExhausted()
                + " wallMs=" + ms);
        for (String key : r.plan().missing().keySet()) {
            assertTrue(key.equals("cheap") || key.equals("costly"),
                    "missing entry '" + key + "' is still craftable - diagnosis was truncated");
        }
    }

    private static long fib(int n) {
        long a = 1, c = 1;
        for (int i = 2; i < n; i++) {
            long next = a + c;
            a = c;
            c = next;
        }
        return c;
    }

    /**
     * 同产物双路线拆分: M has two routes; neither alone can cover the demand, and the optimistic
     * capacity estimate misallocates (route A's capacity double-counts `shared`, which sibling N
     * also needs). Only a mixed allocation — 5 via A plus 5 via B — is feasible. The all-or-nothing
     * recursive search cannot express that split; the contended-node allocation search must.
     */
    @Test
    void splitsDemandAcrossTwoRoutesOfSameProduct() {
        CraftPattern<String> viaShared = new CraftPattern<>(
                "M", 1, List.of(CraftInput.of("a", 1), CraftInput.of("shared", 1)), "viaShared");
        CraftPattern<String> viaB = new CraftPattern<>(
                "M", 1, List.of(CraftInput.of("b", 1)), "viaB");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("M", 1), CraftInput.of("N", 1)))
                .pattern(viaShared)
                .pattern(viaB)
                .pattern("N", 1, List.of(CraftInput.of("shared", 1)))
                .stock("a", 99)
                .stock("b", 5)
                .stock("shared", 15)
                .build();

        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "T", 10);
        System.out.println("[split] 10x T: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " firings=" + r.plan().firings()
                + " usedStock=" + r.plan().usedStock());
        System.out.println("[split] diagnostics=" + r.diagnostics());
        assertTrue(r.plan().feasible(),
                "only a 5/5 split across M's two routes is feasible, got missing=" + r.plan().missing());
        assertEquals(5L, r.plan().firings().getOrDefault(viaShared, 0L));
        assertEquals(5L, r.plan().firings().getOrDefault(viaB, 0L));
        assertEquals(15L, r.plan().usedStock().get("shared"));
    }

    /**
     * 催化剂缩点后走 DAG 路径: the split scenario again, but the shared route also needs a returned
     * catalyst (tool). The catalyst resolves through the seed reserve (one physical seed total, not
     * one per firing), and the contended-allocation search must still find the 5/5 split.
     */
    @Test
    void catalystRouteStillSplitsAcrossRoutes() {
        CraftPattern<String> viaShared = new CraftPattern<>(
                "M", 1,
                List.of(CraftInput.of("a", 1), CraftInput.of("shared", 1), CraftInput.returned("tool", 1)),
                "viaShared");
        CraftPattern<String> viaB = new CraftPattern<>(
                "M", 1, List.of(CraftInput.of("b", 1)), "viaB");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("M", 1), CraftInput.of("N", 1)))
                .pattern(viaShared)
                .pattern(viaB)
                .pattern("N", 1, List.of(CraftInput.of("shared", 1)))
                .stock("a", 99)
                .stock("b", 5)
                .stock("shared", 15)
                .stock("tool", 1)
                .build();

        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "T", 10);
        System.out.println("[catalyst-split] 10x T: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " usedStock=" + r.plan().usedStock());
        assertTrue(r.plan().feasible(),
                "catalyst seed + 5/5 split must be found, got missing=" + r.plan().missing());
        assertEquals(5L, r.plan().firings().getOrDefault(viaShared, 0L));
        assertEquals(5L, r.plan().firings().getOrDefault(viaB, 0L));
        assertEquals(1L, (long) r.plan().usedStock().getOrDefault("tool", 0L));
    }

    /**
     * 固定环截断后走 DAG 路径: ringB <-> ringD form a fixed compress/decompress cycle (9D -> 1B,
     * 1B -> 9D). The back edge is cut at compile, the remainder is a DAG, and the ring-backed route
     * must take exactly the 5 units the shared route cannot cover.
     */
    @Test
    void fixedConversionRingParticipatesInSplit() {
        CraftPattern<String> viaShared = new CraftPattern<>(
                "M", 1, List.of(CraftInput.of("a", 1), CraftInput.of("shared", 1)), "viaShared");
        CraftPattern<String> viaRing = new CraftPattern<>(
                "M", 1, List.of(CraftInput.of("ringB", 1)), "viaRing");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("M", 1), CraftInput.of("N", 1)))
                .pattern(viaShared)
                .pattern(viaRing)
                .pattern("N", 1, List.of(CraftInput.of("shared", 1)))
                .pattern("ringB", 1, List.of(CraftInput.of("ringD", 9)))   // compress
                .pattern("ringD", 9, List.of(CraftInput.of("ringB", 1)))   // decompress (back edge)
                .stock("a", 99)
                .stock("shared", 15)
                .stock("ringD", 45)
                .build();

        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "T", 10);
        System.out.println("[ring-split] 10x T: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " usedStock=" + r.plan().usedStock());
        assertTrue(r.plan().feasible(),
                "ring route must absorb the 5 units shared cannot cover, got missing=" + r.plan().missing());
        assertEquals(5L, r.plan().firings().getOrDefault(viaShared, 0L));
        assertEquals(5L, r.plan().firings().getOrDefault(viaRing, 0L));
        assertEquals(45L, (long) r.plan().usedStock().getOrDefault("ringD", 0L));
    }
}
