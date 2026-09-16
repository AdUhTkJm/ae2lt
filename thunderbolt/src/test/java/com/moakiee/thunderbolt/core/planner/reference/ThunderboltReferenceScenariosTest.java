package com.moakiee.thunderbolt.core.crafting.planner.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ThunderboltReferenceScenariosTest {
    @Test
    void everyFibonacciReferenceHasAtLeastDepth32() {
        var fibonacci = ThunderboltReferenceScenarios.all().stream()
                .filter(scenario -> scenario.id().contains("/fibonacci/"))
                .toList();

        assertFalse(fibonacci.isEmpty());
        assertTrue(fibonacci.stream().allMatch(scenario -> scenario.scale() >= 32));
    }

    @Test
    void ordinarySelfGrowthIsNotAnAe2ParityRequirement() {
        assertTrue(ThunderboltReferenceScenarios.all().stream()
                .noneMatch(scenario -> scenario.id().startsWith("cycle/self-growth-cut/")));
    }

    @Test
    void greedyTrapUsesManyIndependentRandomHashPairs() {
        var scenario = ThunderboltReferenceScenarios.all().stream()
                .filter(candidate -> candidate.id().equals("multi-dag/greedy-trap/minimum"))
                .findFirst()
                .orElseThrow();
        var root = scenario.graph().patternsFor(scenario.target()).getFirst();

        assertTrue(root.inputs().size() >= 32, "greedy trap needs at least 32 independent groups");
        var hashes = new HashSet<Integer>();
        int firstA = 0;
        int firstB = 0;
        for (var groupInput : root.inputs()) {
            var group = scenario.graph().patternsFor(groupInput.key()).getFirst();
            assertEquals(2, group.inputs().size());
            String a = group.inputs().get(0).key();
            String b = group.inputs().get(1).key();
            hashes.add(a.hashCode());
            hashes.add(b.hashCode());

            var ordered = new LinkedHashMap<String, Long>();
            ordered.put(a, 1L);
            ordered.put(b, 1L);
            String actualFirst = Map.copyOf(ordered).keySet().iterator().next();
            if (actualFirst.equals(a)) {
                firstA++;
            } else if (actualFirst.equals(b)) {
                firstB++;
            }
        }

        assertEquals(root.inputs().size() * 2, hashes.size(),
                "random material names must have distinct String hash codes");
        assertTrue(firstA > 0 && firstB > 0,
                "current JVM must exercise both immutable-map orders: A-first="
                        + firstA + " B-first=" + firstB);
    }
}
