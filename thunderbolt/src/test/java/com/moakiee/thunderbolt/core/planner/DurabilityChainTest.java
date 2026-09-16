package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Engine-level tests for the durability chain — built step by step from a "remaining" stepper exactly
 * like the AE2 adapter walks {@code getRemainingKey}, but with plain keys so it is fully offline.
 */
class DurabilityChainTest {

    /** A tool {@code t{d}} degrades to {@code t{d+1}}; at the last index it breaks (remaining == null). */
    private static Function<String, String> degradeUpTo(long maxDurability) {
        return k -> {
            long d = Long.parseLong(k.substring(1));
            return d + 1 < maxDurability ? "t" + (d + 1) : null;
        };
    }

    @Test
    void buildsChainStepByStepAndDerivesN() {
        long d = 1000;
        Map<String, Long> stock = Map.of("t0", 1L); // one full tool

        DurabilityChain<String> chain =
                DurabilityChain.build("t0", degradeUpTo(d), k -> stock.getOrDefault(k, 0L), 8192);

        assertNotNull(chain);
        assertEquals(d, chain.n(), "n is derived purely from chain length, not given");
        assertEquals(d, chain.links().size());
        assertEquals("t0", chain.carrier());
        assertEquals("t0", chain.links().get(0));
        assertEquals("t999", chain.links().get((int) d - 1));
        assertEquals(d, chain.totalUses(), "one full tool = d uses");
    }

    @Test
    void aggregatesPartialDurabilityStock() {
        long d = 100;
        // index i has (d - i) uses left: t0 full (100), t90 -> 10, t99 -> 1.
        Map<String, Long> stock = new HashMap<>();
        stock.put("t0", 2L);   // 2 full tools  -> 200 uses
        stock.put("t90", 1L);  // 10 uses
        stock.put("t99", 3L);  // 1 use each    -> 3 uses

        DurabilityChain<String> chain =
                DurabilityChain.build("t0", degradeUpTo(d), k -> stock.getOrDefault(k, 0L), 8192);

        assertNotNull(chain);
        assertEquals(200 + 10 + 3, chain.totalUses());
    }

    @Test
    void chargeFromStockDrainsMostDegradedFirst() {
        long d = 100;
        Map<String, Long> stock = new HashMap<>();
        stock.put("t0", 1L);   // 100 uses (full)
        stock.put("t95", 1L);  // 5 uses
        stock.put("t98", 2L);  // 2 uses each

        DurabilityChain<String> chain =
                DurabilityChain.build("t0", degradeUpTo(d), k -> stock.getOrDefault(k, 0L), 8192);
        assertNotNull(chain);

        Map<String, Long> drawn = new LinkedHashMap<>();
        chain.chargeFromStock(6, (k, c) -> drawn.merge(k, c, Long::sum)); // need 6 uses

        // Most degraded first: both t98 (cover 4), then one t95 (covers 5, overshoots) — full t0 untouched.
        assertEquals(Map.of("t98", 2L, "t95", 1L), drawn);
        assertNull(drawn.get("t0"), "the full tool is preserved");
    }

    @Test
    void rejectsContainerSingleUseAndOverBudget() {
        // Container: remaining is immediately null (degrades out of its own item group).
        assertNull(DurabilityChain.build("bucket", k -> null, k -> 1L, 8192));
        // Single use (d = 1): t0 breaks at once -> chain too short.
        assertNull(DurabilityChain.build("t0", degradeUpTo(1), k -> 1L, 8192));
        // Chain longer than the budget declines.
        assertNull(DurabilityChain.build("t0", degradeUpTo(10), k -> 0L, 5));
    }

    /**
     * Random stock distributions: charging any {@code uses ≤ totalUses} covers the demand and overshoots
     * by less than one tool ({@code < n}), and never draws more of a level than is in stock.
     */
    @Test
    void chargeFromStockIsExactWithinOnePartialTool() {
        Random rnd = new Random(7L);
        for (int iter = 0; iter < 3000; iter++) {
            long d = 1 + rnd.nextInt(2048);
            Map<String, Long> stock = new HashMap<>();
            int variants = 1 + rnd.nextInt(5);
            for (int v = 0; v < variants; v++) {
                long idx = rnd.nextInt((int) d);            // a tool sitting at link idx -> (d-idx) uses
                stock.merge("t" + idx, 1L + rnd.nextInt(4), Long::sum);
            }
            DurabilityChain<String> chain =
                    DurabilityChain.build("t0", degradeUpTo(d), k -> stock.getOrDefault(k, 0L), 8192);
            assertNotNull(chain);

            long total = chain.totalUses();
            if (total == 0) {
                continue;
            }
            long uses = 1 + (long) (rnd.nextDouble() * (total - 1)); // in [1, total]

            Map<String, Long> drawn = new HashMap<>();
            chain.chargeFromStock(uses, (k, c) -> drawn.merge(k, c, Long::sum));

            long covered = 0;
            for (Map.Entry<String, Long> e : drawn.entrySet()) {
                long idx = Long.parseLong(e.getKey().substring(1));
                long perTool = d - idx;
                covered += perTool * e.getValue();
                assertTrue(e.getValue() <= stock.get(e.getKey()),
                        "never draw more of a level than in stock @" + e.getKey());
            }
            String msg = "iter=" + iter + " d=" + d + " uses=" + uses + " covered=" + covered;
            assertTrue(covered >= uses, msg);
            assertTrue(covered - uses < d, msg + " (overshoot < one tool)");
        }
    }

    @Test
    void unusedLinksAreFullyPreserved() {
        long d = 50;
        Map<String, Long> stock = new HashMap<>();
        stock.put("t0", 10L); // plenty of full tools
        DurabilityChain<String> chain =
                DurabilityChain.build("t0", degradeUpTo(d), k -> stock.getOrDefault(k, 0L), 8192);
        assertNotNull(chain);

        Map<String, Long> drawn = new HashMap<>();
        chain.chargeFromStock(d + 1, (k, c) -> drawn.merge(k, c, Long::sum)); // just over one tool -> 2 full tools

        assertEquals(2L, drawn.get("t0"), "ceil((d+1)/d) = 2 full tools drawn");
        List<String> links = chain.links();
        assertEquals("t0", links.get(0));
        assertEquals(d, links.size());
    }
}
