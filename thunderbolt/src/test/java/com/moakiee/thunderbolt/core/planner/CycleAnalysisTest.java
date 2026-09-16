package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CycleAnalysisTest {

    @Test
    void classifiesConservativeMultiTierCompressionAsPureConversion() {
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("ingot", 1, List.of(CraftInput.of("nugget", 9)))
                .pattern("nugget", 9, List.of(CraftInput.of("ingot", 1)))
                .pattern("block", 1, List.of(CraftInput.of("ingot", 9)))
                .pattern("ingot", 9, List.of(CraftInput.of("block", 1)))
                .pattern("compressed_block", 1, List.of(CraftInput.of("block", 9)))
                .pattern("block", 9, List.of(CraftInput.of("compressed_block", 1)))
                .build();

        CycleAnalysis<String> analysis = CycleAnalysis.analyze(graph, "compressed_block");

        for (String member : List.of("nugget", "ingot", "block", "compressed_block")) {
            assertEquals(CycleAnalysis.Kind.PURE_CONVERSION, analysis.kindOf(member));
        }
    }

    @Test
    void classifiesConversionWithExternalToolAsCatalyzed() {
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("stone", 1, List.of(CraftInput.of("cobble", 1)))
                .pattern("cobble", 1, List.of(
                        CraftInput.of("stone", 1), CraftInput.of("tool", 1)))
                .build();

        CycleAnalysis<String> analysis = CycleAnalysis.analyze(graph, "stone");

        assertEquals(CycleAnalysis.Kind.CATALYZED_CONVERSION, analysis.kindOf("stone"));
        assertEquals(CycleAnalysis.Kind.CATALYZED_CONVERSION, analysis.kindOf("cobble"));
    }

    @Test
    void keepsReturnedCatalystStatesOutOfConversionReorientation() {
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.returned("B", 1)))
                .pattern("B", 1, List.of(CraftInput.returned("A", 1)))
                .build();

        CycleAnalysis<String> analysis = CycleAnalysis.analyze(graph, "A");

        assertEquals(CycleAnalysis.Kind.CATALYST_STATE, analysis.kindOf("A"));
        assertEquals(CycleAnalysis.Kind.CATALYST_STATE, analysis.kindOf("B"));
    }

    @Test
    void classifiesGainCycleAsLossyAndMultiInputCycleAsComplex() {
        CraftGraph<String> gain = CraftGraph.<String>builder()
                .pattern("A", 2, List.of(CraftInput.of("B", 1)))
                .pattern("B", 2, List.of(CraftInput.of("A", 1)))
                .build();
        CraftGraph<String> multiInput = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1), CraftInput.of("C", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .pattern("C", 1, List.of(CraftInput.of("A", 1)))
                .build();

        // A gain/lossy ring is still one mass-balanced direction per orientation, so it is now
        // classified LOSSY_CONVERSION and may be reoriented; structurally weird cycles stay COMPLEX.
        assertEquals(CycleAnalysis.Kind.LOSSY_CONVERSION,
                CycleAnalysis.analyze(gain, "A").kindOf("A"));
        assertEquals(CycleAnalysis.Kind.COMPLEX,
                CycleAnalysis.analyze(multiInput, "A").kindOf("A"));
    }

    @Test
    void exposesConservativePairInsideToolExpandedComplexScc() {
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern("stone", 1, List.of(CraftInput.of("cobble", 1)))
                .pattern("cobble", 1, List.of(
                        CraftInput.of("stone", 1), CraftInput.of("tool", 1)))
                .pattern("tool", 1, List.of(CraftInput.of("cobble", 1)))
                .build();

        CycleAnalysis<String> analysis = CycleAnalysis.analyze(graph, "stone");

        assertEquals(CycleAnalysis.Kind.COMPLEX, analysis.kindOf("cobble"));
        assertTrue(analysis.mayReorient("stone"));
        assertTrue(analysis.mayReorient("cobble"));
    }
}
