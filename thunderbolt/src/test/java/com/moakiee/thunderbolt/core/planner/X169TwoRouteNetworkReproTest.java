package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * Full two-route reproduction of the in-world "x=169" wall: every cell-component tier has both its
 * normal mod recipe (3x previous tier + processor, providers at x=165) and the adversarial
 * "previous + one-before-previous" 1+1 pattern (providers at x=169, Fibonacci demand). Generated
 * from hmcl/exports/测试-ae2-patterns-2026-08-04.json.
 */
class X169TwoRouteNetworkReproTest {

    private static final long RAW_STOCK = 1_000_000_000_000L;

    private static CraftGraph<String> buildFullGraph() {
        return buildFullGraph(RAW_STOCK);
    }

    private static CraftGraph<String> buildFullGraph(long rawStock) {
        CraftGraph.Builder<String> b = CraftGraph.builder();
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_16k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_4k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_16k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_16k", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_1k", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_4k", 1)), "fib:ae2omnicells:complex_omni_cell_component_16k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_16m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_4m", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_16m"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_16m", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_1m", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_4m", 1)), "fib:ae2omnicells:complex_omni_cell_component_16m"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_1k", 1, List.of(CraftInput.of("ae2omnicells:charged_ender_ingot", 2), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:omni_cell_component_1k", 1), CraftInput.of("minecraft:glowstone_dust", 4), CraftInput.of("neoecoae:superconducting_processor", 1)), "recipe:ae2omnicells:complex_omni_cell_component_1k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_1k", 1, List.of(CraftInput.of("ae2omnicells:omni_cell_component_256m", 1), CraftInput.of("ae2omnicells:omni_cell_component_64m", 1)), "fib:ae2omnicells:complex_omni_cell_component_1k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_1m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_256k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_1m"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_1m", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_256k", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_64k", 1)), "fib:ae2omnicells:complex_omni_cell_component_1m"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_256k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_64k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_256k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_256k", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_16k", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_64k", 1)), "fib:ae2omnicells:complex_omni_cell_component_256k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_256m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_64m", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_256m"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_256m", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_16m", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_64m", 1)), "fib:ae2omnicells:complex_omni_cell_component_256m"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_4k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_1k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_4k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_4k", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_1k", 1), CraftInput.of("ae2omnicells:omni_cell_component_256m", 1)), "fib:ae2omnicells:complex_omni_cell_component_4k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_4m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_1m", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_4m"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_4m", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_1m", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_256k", 1)), "fib:ae2omnicells:complex_omni_cell_component_4m"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_64k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_16k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_64k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_64k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_16k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_64k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_64k", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_16k", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_4k", 1)), "fib:ae2omnicells:complex_omni_cell_component_64k"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_64m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:complex_link_processor", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_16m", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:complex_omni_cell_component_64m"));
        b.pattern(new CraftPattern<>("ae2omnicells:complex_omni_cell_component_64m", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_16m", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_4m", 1)), "fib:ae2omnicells:complex_omni_cell_component_64m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_16k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:omni_cell_component_4k", 3), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:omni_cell_component_16k"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_16k", 1, List.of(CraftInput.of("ae2omnicells:omni_cell_component_1k", 1), CraftInput.of("ae2omnicells:omni_cell_component_4k", 1)), "fib:ae2omnicells:omni_cell_component_16k"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_16m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:omni_cell_component_4m", 3), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:omni_cell_component_16m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_16m", 1, List.of(CraftInput.of("ae2omnicells:omni_cell_component_1m", 1), CraftInput.of("ae2omnicells:omni_cell_component_4m", 1)), "fib:ae2omnicells:omni_cell_component_16m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_1k", 1, List.of(CraftInput.of("ae2lt:lightning_cell_component_ii", 1), CraftInput.of("ae2omnicells:ender_ingot", 2), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4), CraftInput.of("neoecoae:superconducting_processor", 1)), "recipe:ae2omnicells:omni_cell_component_1k"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_1k", 1, List.of(CraftInput.of("appflux:core_256m", 1), CraftInput.of("appflux:core_64m", 1)), "fib:ae2omnicells:omni_cell_component_1k"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_1m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:omni_cell_component_256k", 3), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:omni_cell_component_1m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_1m", 1, List.of(CraftInput.of("ae2omnicells:omni_cell_component_256k", 1), CraftInput.of("ae2omnicells:omni_cell_component_64k", 1)), "fib:ae2omnicells:omni_cell_component_1m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_256k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:omni_cell_component_64k", 3), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:omni_cell_component_256k"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_256k", 1, List.of(CraftInput.of("ae2omnicells:omni_cell_component_16k", 1), CraftInput.of("ae2omnicells:omni_cell_component_64k", 1)), "fib:ae2omnicells:omni_cell_component_256k"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_256m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:omni_cell_component_64m", 3), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:omni_cell_component_256m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_256m", 1, List.of(CraftInput.of("ae2omnicells:omni_cell_component_16m", 1), CraftInput.of("ae2omnicells:omni_cell_component_64m", 1)), "fib:ae2omnicells:omni_cell_component_256m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_4k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:omni_cell_component_1k", 3), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:omni_cell_component_4k"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_4k", 1, List.of(CraftInput.of("ae2omnicells:omni_cell_component_1k", 1), CraftInput.of("appflux:core_256m", 1)), "fib:ae2omnicells:omni_cell_component_4k"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_4m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:omni_cell_component_1m", 3), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:omni_cell_component_4m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_4m", 1, List.of(CraftInput.of("ae2omnicells:omni_cell_component_1m", 1), CraftInput.of("ae2omnicells:omni_cell_component_256k", 1)), "fib:ae2omnicells:omni_cell_component_4m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_64k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:omni_cell_component_16k", 3), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:omni_cell_component_64k"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_64m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:omni_cell_component_16m", 3), CraftInput.of("ae2omnicells:omni_link_processor", 1), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:omni_cell_component_64m"));
        b.pattern(new CraftPattern<>("ae2omnicells:omni_cell_component_64m", 1, List.of(CraftInput.of("ae2omnicells:omni_cell_component_16m", 1), CraftInput.of("ae2omnicells:omni_cell_component_4m", 1)), "fib:ae2omnicells:omni_cell_component_64m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_16k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_4k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:quantum_omni_cell_component_16k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_16k", 1, List.of(CraftInput.of("ae2omnicells:quantum_omni_cell_component_1k", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_4k", 1)), "fib:ae2omnicells:quantum_omni_cell_component_16k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_16m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_4m", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:quantum_omni_cell_component_16m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_16m", 1, List.of(CraftInput.of("ae2omnicells:quantum_omni_cell_component_1m", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_4m", 1)), "fib:ae2omnicells:quantum_omni_cell_component_16m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_1k", 1, List.of(CraftInput.of("ae2:ender_dust", 4), CraftInput.of("ae2omnicells:charged_ender_ingot", 2), CraftInput.of("ae2omnicells:complex_omni_cell_component_1k", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("neoecoae:superconducting_processor", 1)), "recipe:ae2omnicells:quantum_omni_cell_component_1k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_1k", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_256m", 1), CraftInput.of("ae2omnicells:complex_omni_cell_component_64m", 1)), "fib:ae2omnicells:quantum_omni_cell_component_1k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_1m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_256k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:quantum_omni_cell_component_1m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_1m", 1, List.of(CraftInput.of("ae2omnicells:quantum_omni_cell_component_256k", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_64k", 1)), "fib:ae2omnicells:quantum_omni_cell_component_1m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_256k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_64k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:quantum_omni_cell_component_256k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_256k", 1, List.of(CraftInput.of("ae2omnicells:quantum_omni_cell_component_16k", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_64k", 1)), "fib:ae2omnicells:quantum_omni_cell_component_256k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_256m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_64m", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:quantum_omni_cell_component_256m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_256m", 1, List.of(CraftInput.of("ae2omnicells:quantum_omni_cell_component_16m", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_64m", 1)), "fib:ae2omnicells:quantum_omni_cell_component_256m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_4k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_1k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:quantum_omni_cell_component_4k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_4k", 1, List.of(CraftInput.of("ae2omnicells:complex_omni_cell_component_256m", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_1k", 1)), "fib:ae2omnicells:quantum_omni_cell_component_4k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_4m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_1m", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:quantum_omni_cell_component_4m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_4m", 1, List.of(CraftInput.of("ae2omnicells:quantum_omni_cell_component_1m", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_256k", 1)), "fib:ae2omnicells:quantum_omni_cell_component_4m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_64k", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_16k", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:quantum_omni_cell_component_64k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_64k", 1, List.of(CraftInput.of("ae2omnicells:quantum_omni_cell_component_16k", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_4k", 1)), "fib:ae2omnicells:quantum_omni_cell_component_64k"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_64m", 1, List.of(CraftInput.of("ae2:quartz_glass", 1), CraftInput.of("ae2omnicells:multidimensional_expansion_processor", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_16m", 3), CraftInput.of("minecraft:redstone", 4)), "recipe:ae2omnicells:quantum_omni_cell_component_64m"));
        b.pattern(new CraftPattern<>("ae2omnicells:quantum_omni_cell_component_64m", 1, List.of(CraftInput.of("ae2omnicells:quantum_omni_cell_component_16m", 1), CraftInput.of("ae2omnicells:quantum_omni_cell_component_4m", 1)), "fib:ae2omnicells:quantum_omni_cell_component_64m"));
        b.pattern(new CraftPattern<>("appflux:core_16k", 1, List.of(CraftInput.of("ae2:fluix_dust", 4), CraftInput.of("ae2:quartz_vibrant_glass", 1), CraftInput.of("appflux:core_4k", 3), CraftInput.of("appflux:energy_processor", 1)), "recipe:appflux:core_16k"));
        b.pattern(new CraftPattern<>("appflux:core_16k", 1, List.of(CraftInput.of("appflux:core_1k", 1), CraftInput.of("appflux:core_4k", 1)), "fib:appflux:core_16k"));
        b.pattern(new CraftPattern<>("appflux:core_16m", 1, List.of(CraftInput.of("ae2:engineering_processor", 1), CraftInput.of("ae2:quartz_vibrant_glass", 1), CraftInput.of("appflux:core_4m", 3), CraftInput.of("mekanism:dust_diamond", 4)), "recipe:appflux:core_16m"));
        b.pattern(new CraftPattern<>("appflux:core_16m", 1, List.of(CraftInput.of("appflux:core_1m", 1), CraftInput.of("appflux:core_4m", 1)), "fib:appflux:core_16m"));
        b.pattern(new CraftPattern<>("appflux:core_1k", 1, List.of(CraftInput.of("ae2:certus_quartz_dust", 4), CraftInput.of("appflux:redstone_crystal", 4), CraftInput.of("mekanism:basic_energy_cube", 1)), "recipe:appflux:core_1k"));
        b.pattern(new CraftPattern<>("appflux:core_1m", 1, List.of(CraftInput.of("ae2:ender_dust", 4), CraftInput.of("ae2:engineering_processor", 1), CraftInput.of("ae2:quartz_vibrant_glass", 1), CraftInput.of("appflux:core_256k", 3)), "recipe:appflux:core_1m"));
        b.pattern(new CraftPattern<>("appflux:core_1m", 1, List.of(CraftInput.of("appflux:core_256k", 1), CraftInput.of("appflux:core_64k", 1)), "fib:appflux:core_1m"));
        b.pattern(new CraftPattern<>("appflux:core_256k", 1, List.of(CraftInput.of("ae2:ender_dust", 4), CraftInput.of("ae2:quartz_vibrant_glass", 1), CraftInput.of("appflux:core_64k", 3), CraftInput.of("appflux:energy_processor", 1)), "recipe:appflux:core_256k"));
        b.pattern(new CraftPattern<>("appflux:core_256k", 1, List.of(CraftInput.of("appflux:core_16k", 1), CraftInput.of("appflux:core_64k", 1)), "fib:appflux:core_256k"));
        b.pattern(new CraftPattern<>("appflux:core_256m", 1, List.of(CraftInput.of("ae2:engineering_processor", 1), CraftInput.of("ae2:quartz_vibrant_glass", 1), CraftInput.of("appflux:core_64m", 3), CraftInput.of("mekanism:dust_emerald", 4)), "recipe:appflux:core_256m"));
        b.pattern(new CraftPattern<>("appflux:core_256m", 1, List.of(CraftInput.of("appflux:core_16m", 1), CraftInput.of("appflux:core_64m", 1)), "fib:appflux:core_256m"));
        b.pattern(new CraftPattern<>("appflux:core_4k", 1, List.of(CraftInput.of("ae2:certus_quartz_dust", 4), CraftInput.of("ae2:quartz_vibrant_glass", 1), CraftInput.of("appflux:core_1k", 3), CraftInput.of("appflux:energy_processor", 1)), "recipe:appflux:core_4k"));
        b.pattern(new CraftPattern<>("appflux:core_4m", 1, List.of(CraftInput.of("ae2:engineering_processor", 1), CraftInput.of("ae2:quartz_vibrant_glass", 1), CraftInput.of("appflux:core_1m", 3), CraftInput.of("mekanism:dust_diamond", 4)), "recipe:appflux:core_4m"));
        b.pattern(new CraftPattern<>("appflux:core_4m", 1, List.of(CraftInput.of("appflux:core_1m", 1), CraftInput.of("appflux:core_256k", 1)), "fib:appflux:core_4m"));
        b.pattern(new CraftPattern<>("appflux:core_64k", 1, List.of(CraftInput.of("ae2:fluix_dust", 4), CraftInput.of("ae2:quartz_vibrant_glass", 1), CraftInput.of("appflux:core_16k", 3), CraftInput.of("appflux:energy_processor", 1)), "recipe:appflux:core_64k"));
        b.pattern(new CraftPattern<>("appflux:core_64k", 1, List.of(CraftInput.of("appflux:core_16k", 1), CraftInput.of("appflux:core_4k", 1)), "fib:appflux:core_64k"));
        b.pattern(new CraftPattern<>("appflux:core_64m", 1, List.of(CraftInput.of("ae2:engineering_processor", 1), CraftInput.of("ae2:quartz_vibrant_glass", 1), CraftInput.of("appflux:core_16m", 3), CraftInput.of("mekanism:dust_emerald", 4)), "recipe:appflux:core_64m"));
        b.pattern(new CraftPattern<>("appflux:core_64m", 1, List.of(CraftInput.of("appflux:core_16m", 1), CraftInput.of("appflux:core_4m", 1)), "fib:appflux:core_64m"));

        b.stock("ae2:certus_quartz_dust", rawStock);
        b.stock("ae2:ender_dust", rawStock);
        b.stock("ae2:engineering_processor", rawStock);
        b.stock("ae2:fluix_dust", rawStock);
        b.stock("ae2:quartz_glass", rawStock);
        b.stock("ae2:quartz_vibrant_glass", rawStock);
        b.stock("ae2lt:lightning_cell_component_ii", rawStock);
        b.stock("ae2omnicells:charged_ender_ingot", rawStock);
        b.stock("ae2omnicells:complex_link_processor", rawStock);
        b.stock("ae2omnicells:ender_ingot", rawStock);
        b.stock("ae2omnicells:multidimensional_expansion_processor", rawStock);
        b.stock("ae2omnicells:omni_link_processor", rawStock);
        b.stock("appflux:energy_processor", rawStock);
        b.stock("appflux:redstone_crystal", rawStock);
        b.stock("mekanism:basic_energy_cube", rawStock);
        b.stock("mekanism:dust_diamond", rawStock);
        b.stock("mekanism:dust_emerald", rawStock);
        b.stock("minecraft:glowstone_dust", rawStock);
        b.stock("minecraft:redstone", rawStock);
        b.stock("neoecoae:superconducting_processor", rawStock);
        return b.build();
    }

    private static void report(String label, PlanningResult<String> r, long ms) {
        Map<String, Long> byRoute = new TreeMap<>();
        r.plan().firings().forEach((p, n) -> byRoute.merge(
                String.valueOf(p.source()).startsWith("fib:") ? "fib" : "recipe", n, Long::sum));
        System.out.println("[x169-full] " + label + ": feasible=" + r.plan().feasible()
                + " missing=" + r.plan().missing()
                + " budgetExhausted=" + r.plan().budgetExhausted()
                + " firingsByRoute=" + byRoute
                + " wallMs=" + ms);
        System.out.println("[x169-full] " + label + " diagnostics=" + r.diagnostics());
    }

    @Test
    void quantum256mViaSaneRoutes() {
        CraftGraph<String> g = buildFullGraph();
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "ae2omnicells:quantum_omni_cell_component_256m", 1);
        report("1x quantum_256m", r, (System.nanoTime() - t0) / 1_000_000);
        assertTrue(r.plan().feasible());
    }

    @Test
    void quantum256mThousand() {
        CraftGraph<String> g = buildFullGraph();
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "ae2omnicells:quantum_omni_cell_component_256m", 1000);
        report("1000x quantum_256m", r, (System.nanoTime() - t0) / 1_000_000);
        assertTrue(r.plan().feasible());
    }

    @Test
    void quantum256mBillion() {
        CraftGraph<String> g = buildFullGraph();
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "ae2omnicells:quantum_omni_cell_component_256m", 1_000_000_000L);
        report("1e9 quantum_256m", r, (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * In-game symptom (screenshot 2026-08-04): with scarce raw stock the plan showed truncated
     * missing entries like "2 x complex_omni_cell_component_16k" instead of expanding those
     * intermediates through their own patterns. Missing must only ever name items with no
     * producing pattern (true raws).
     */
    @Test
    void starvedRequestExpandsMissingToRawLeaves() {
        // Scarce raw stock (1k each), mimicking the in-game network state.
        CraftGraph<String> g = buildFullGraph(1_000L);
        long t0 = System.nanoTime();
        PlanningResult<String> r = CraftPlannerV2.planDetailed(g, "ae2omnicells:quantum_omni_cell_component_256m", 1000);
        report("starved 1000x quantum_256m", r, (System.nanoTime() - t0) / 1_000_000);
        assertFalse(r.plan().feasible());
        for (String key : r.plan().missing().keySet()) {
            assertTrue(g.patternsFor(key).isEmpty(),
                    "missing entry '" + key + "' is still craftable - diagnosis was truncated");
        }
    }
}
