package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Cycle-cut regressions: the DFS drops whichever ring pattern closes a cycle first, so the cut
 * position depends on sibling arrival order, and a cut in the wrong direction must be recoverable
 * by reorientation — including for non-conservative (lossy/gainful) conversion rings that the
 * conservative-weight proof used to classify as COMPLEX and never retry.
 */
class CycleCutReorientationTest {

    /**
     * Lossy two-way ring (3B -> 1A, 3A -> 1B; round trip 1/9). T consumes B first, so the DFS cuts
     * A's producer; all stock sits on B. Previously COMPLEX -> no reorientation -> spurious missing.
     * A lossy ring is still one mass-balanced direction per orientation, so reorienting is sound.
     */
    @Test
    void lossyConversionRingReorients() {
        CraftPattern<String> aFromB = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("B", 3)), "aFromB");
        CraftPattern<String> bFromA = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("A", 3)), "bFromA");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("B", 1), CraftInput.of("A", 1)))
                .pattern(aFromB)
                .pattern(bFromA)
                .stock("B", 10)
                .build();

        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "T", 1);
        System.out.println("[lossy-ring] 1x T: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " usedStock=" + r.plan().usedStock());
        assertTrue(r.plan().feasible(),
                "reorienting the lossy ring makes A craftable from B stock, got missing="
                        + r.plan().missing());
        assertEquals(1L, r.plan().firings().getOrDefault(aFromB, 0L));
        assertEquals(4L, (long) r.plan().usedStock().get("B")); // 1 direct + 3 for A
    }

    /**
     * Conservative three-member ring (9C->1B, 9B->1A, and the reverses). T consumes A then C; the
     * DFS entering through A cuts the ring so that C's compressing producer survives but the
     * DECOMPRESSING direction toward C is gone, while all stock sits on A. The shortfall then
     * surfaces on C — a cycle member — while the recorded cut output is a different member, so the
     * member-granular blame must still enqueue the reorientation.
     */
    @Test
    void shortfallOnOtherCycleMemberStillTriggersReorientation() {
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("C", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 9)))   // compress toward A
                .pattern("B", 1, List.of(CraftInput.of("C", 9)))
                .pattern("C", 9, List.of(CraftInput.of("B", 1)))   // decompress toward C
                .pattern("B", 9, List.of(CraftInput.of("A", 1)))
                .stock("A", 5)
                .build();

        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "T", 1);
        System.out.println("[member-blame] 1x T: feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " usedStock=" + r.plan().usedStock());
        assertTrue(r.plan().feasible(),
                "C must be reachable by decompressing A stock after reorientation, got missing="
                        + r.plan().missing());
        assertEquals(2L, (long) r.plan().usedStock().get("A")); // 1 direct + 1 decompressed
    }
}
