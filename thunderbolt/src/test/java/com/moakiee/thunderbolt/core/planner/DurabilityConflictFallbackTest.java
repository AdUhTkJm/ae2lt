package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for the conservative graph representation used when one physical durability item
 * appears under incompatible pattern semantics. The AE2 adapter emits these as ordinary whole-item
 * inputs rather than creating one independently stocked durability pool per pattern.
 */
class DurabilityConflictFallbackTest {

    @Test
    void conflictingPatternsShareOnePhysicalToolPool() {
        CraftPattern<String> left = new CraftPattern<>(
                "left", 1, List.of(CraftInput.of("tool", 1)), "left-pattern");
        CraftPattern<String> right = new CraftPattern<>(
                "right", 1, List.of(CraftInput.of("tool", 1)), "right-pattern");
        CraftPattern<String> result = new CraftPattern<>(
                "result", 1,
                List.of(CraftInput.of("left", 1), CraftInput.of("right", 1)),
                "result-pattern");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(left)
                .pattern(right)
                .pattern(result)
                .stock("tool", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertFalse(plan.feasible(), "one physical tool must not be duplicated into two pattern pools");
        assertEquals(1L, plan.usedStock().get("tool"));
        assertEquals(1L, plan.missing().get("tool"));
    }

    @Test
    void conservativeToolCanStillBeCraftedOnDemand() {
        CraftPattern<String> tool = new CraftPattern<>(
                "tool", 1, List.of(CraftInput.of("ingot", 1)), "tool-pattern");
        CraftPattern<String> left = new CraftPattern<>(
                "left", 1, List.of(CraftInput.of("tool", 1)), "left-pattern");
        CraftPattern<String> right = new CraftPattern<>(
                "right", 1, List.of(CraftInput.of("tool", 1)), "right-pattern");
        CraftPattern<String> result = new CraftPattern<>(
                "result", 1,
                List.of(CraftInput.of("left", 1), CraftInput.of("right", 1)),
                "result-pattern");
        CraftGraph<String> graph = CraftGraph.<String>builder()
                .pattern(tool)
                .pattern(left)
                .pattern(right)
                .pattern(result)
                .stock("tool", 1)
                .stock("ingot", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(graph, "result", 1);

        assertTrue(plan.feasible());
        assertEquals(1L, plan.usedStock().get("tool"));
        assertEquals(1L, plan.usedStock().get("ingot"));
        assertEquals(1L, plan.firings().get(tool));
    }
}
