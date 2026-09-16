package com.moakiee.thunderbolt.core.crafting.planner.reference;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftOutput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPattern;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlan;
import com.moakiee.thunderbolt.core.crafting.planner.Sat;

/** Replays the exact reported firing multiset against the scenario inventory. */
final class ReferencePlanReplay {
    private ReferencePlanReplay() {
    }

    static boolean completes(
            ReferenceScenario scenario, CraftPlan<String> plan, boolean supplyReportedMissing) {
        if (plan == null || !plan.supported()) {
            return false;
        }

        var inventory = new HashMap<String, Long>();
        var supplied = supplyReportedMissing ? positive(plan.missing()) : Map.<String, Long>of();
        for (var entry : supplied.entrySet()) {
            set(inventory, scenario.graph(), entry.getKey(), entry.getValue());
        }

        var remaining = new LinkedHashMap<CraftPattern<String>, Long>();
        for (var entry : plan.firings().entrySet()) {
            CraftPattern<String> pattern = entry.getKey();
            Long count = entry.getValue();
            if (pattern == null || count == null || count < 0
                    || !scenario.graph().patternsFor(pattern.output()).contains(pattern)) {
                return false;
            }
            if (count > 0) {
                remaining.put(pattern, count);
            }
        }

        while (!remaining.isEmpty()) {
            boolean progressed = false;
            var iterator = remaining.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                long executable = maximumExecutable(
                        scenario.graph(), entry.getKey(), entry.getValue(), inventory, supplied);
                if (executable <= 0) {
                    continue;
                }
                fire(scenario.graph(), entry.getKey(), executable, inventory);
                long left = entry.getValue() - executable;
                if (left == 0) {
                    iterator.remove();
                } else {
                    entry.setValue(left);
                }
                progressed = true;
            }
            if (!progressed) {
                return false;
            }
        }

        return amount(inventory, scenario.graph(), scenario.target()) >= scenario.amount();
    }

    private static long maximumExecutable(
            CraftGraph<String> graph,
            CraftPattern<String> pattern,
            long requested,
            Map<String, Long> inventory,
            Map<String, Long> supplied) {
        long low = 0;
        long high = requested;
        while (low < high) {
            long middle = low + (high - low + 1) / 2;
            if (canFire(graph, pattern, middle, inventory, supplied)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    private static boolean canFire(
            CraftGraph<String> graph,
            CraftPattern<String> pattern,
            long times,
            Map<String, Long> inventory,
            Map<String, Long> supplied) {
        var ordinaryRequired = new HashMap<String, Long>();
        var reusableRequired = new HashMap<ReusableRequirement, Long>();
        for (CraftInput<String> input : pattern.inputs()) {
            long units = input.unitsFor(times);
            if (input.reusableStockSource() == null) {
                ordinaryRequired.merge(input.key(), units, Sat::add);
            } else {
                reusableRequired.merge(
                        new ReusableRequirement(input.reusableStockSource(), input.key()),
                        units, Sat::add);
            }
        }
        for (var entry : ordinaryRequired.entrySet()) {
            if (amount(inventory, graph, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        for (var entry : reusableRequired.entrySet()) {
            var requirement = entry.getKey();
            long available = Sat.add(
                    graph.reusableStock(requirement.source(), requirement.key()),
                    supplied.getOrDefault(requirement.key(), 0L));
            if (available < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static void fire(
            CraftGraph<String> graph,
            CraftPattern<String> pattern,
            long times,
            Map<String, Long> inventory) {
        for (CraftInput<String> input : pattern.inputs()) {
            if (input.reusableStockSource() == null
                    && (!input.returned() || input.uses() != CraftInput.INFINITE_USES)) {
                consume(inventory, graph, input.key(), input.unitsFor(times));
            }
        }
        insert(inventory, graph, pattern.output(), Sat.mul(pattern.outputAmount(), times));
        for (CraftOutput<String> output : pattern.byproducts()) {
            insert(inventory, graph, output.key(), Sat.mul(output.amount(), times));
        }
    }

    private static Map<String, Long> positive(Map<String, Long> values) {
        var result = new HashMap<String, Long>();
        values.forEach((key, value) -> {
            if (key != null && value != null && value > 0) {
                result.merge(key, value, Sat::add);
            }
        });
        return result;
    }

    private static long amount(
            Map<String, Long> inventory, CraftGraph<String> graph, String key) {
        return inventory.computeIfAbsent(key, graph::stock);
    }

    private static void set(
            Map<String, Long> inventory, CraftGraph<String> graph, String key, long added) {
        inventory.put(key, Sat.add(graph.stock(key), added));
    }

    private static void consume(
            Map<String, Long> inventory, CraftGraph<String> graph, String key, long amount) {
        inventory.put(key, ReferencePlanReplay.amount(inventory, graph, key) - amount);
    }

    private static void insert(
            Map<String, Long> inventory, CraftGraph<String> graph, String key, long amount) {
        inventory.put(key, Sat.add(ReferencePlanReplay.amount(inventory, graph, key), amount));
    }

    private record ReusableRequirement(
            com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource source, String key) {
    }
}
