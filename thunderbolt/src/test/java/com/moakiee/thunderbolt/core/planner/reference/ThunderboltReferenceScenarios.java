package com.moakiee.thunderbolt.core.crafting.planner.reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftOutput;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

/** Canonical offline cases from the author-facing reference standard. */
public final class ThunderboltReferenceScenarios {
    private static final long UNBOUNDED_STOCK = 1_000_000_000_000L;
    private static final int REFERENCE_DAG_DEPTH = 32;
    private static final int GREEDY_TRAP_GROUPS = 32;
    private static final long GREEDY_TRAP_HASH_SEED = 0x5EED_C0DE_6A09_E667L;
    private static final String GREEDY_TRAP_TARGET = "greedy_trap_target";
    private static final List<GreedyTrapGroup> GREEDY_TRAP_DATA = greedyTrapData();

    private ThunderboltReferenceScenarios() {
    }

    public static List<ReferenceScenario> all() {
        var result = new ArrayList<ReferenceScenario>();
        addDispersedSingleDag(result);
        addFibonacciSingleDag(result, REFERENCE_DAG_DEPTH);
        addGreedyTrapMultiDag(result, 64);
        addFibonacciMultiDag(result, REFERENCE_DAG_DEPTH);
        addConversionCycle(result);
        // AE2 rejects ordinary patterns that consume and produce the same key as recursive.
        // Self-growth is therefore an optional extension, not a parity capability requirement.
        addCatalyst(result, 1_000);
        addRawCatalystLoop(result, 8);
        addLossyFeedbackLoop(result, 8);
        addDurability(result, 100, 10_000);
        addFuzzyVariant(result, 1_000);
        return List.copyOf(result);
    }

    private static void addDispersedSingleDag(List<ReferenceScenario> out) {
        Map<String, Long> minimum = Map.of("D", 4L, "E", 4L, "F", 4L, "G", 4L);
        Map<String, Long> starved = Map.of("D", 2L, "E", 4L, "F", 4L, "G", 3L);
        Map<String, Long> missing = Map.of("D", 2L, "G", 1L);
        addThreeModes(out, "single-dag/dispersed", ReferenceCapability.SINGLE_DAG, 3,
                "A", 4, minimum, starved, List.of(missing),
                ThunderboltReferenceScenarios::dispersedSingleDag);
    }

    private static CraftGraph<String> dispersedSingleDag(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1), CraftInput.of("C", 1)))
                .pattern("B", 1, List.of(CraftInput.of("D", 1), CraftInput.of("E", 1)))
                .pattern("C", 1, List.of(CraftInput.of("F", 1), CraftInput.of("G", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addFibonacciSingleDag(List<ReferenceScenario> out, int depth) {
        Map<String, Long> minimum = fibonacciSingleDemand(depth);
        Map<String, Long> starved = new LinkedHashMap<>(minimum);
        starved.compute("X0", (key, value) -> value - 3L);
        starved.compute("X1", (key, value) -> value - 5L);
        addThreeModes(out, "single-dag/fibonacci", ReferenceCapability.SINGLE_DAG, depth,
                "X" + depth, 1, minimum, starved, List.of(Map.of("X0", 3L, "X1", 5L)),
                stock -> fibonacciSingleDag(depth, stock));
    }

    private static CraftGraph<String> fibonacciSingleDag(int depth, Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder();
        for (int i = 2; i <= depth; i++) {
            builder.pattern("X" + i, 1, List.of(
                    CraftInput.of("X" + (i - 1), 1),
                    CraftInput.of("X" + (i - 2), 1)));
        }
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static Map<String, Long> fibonacciSingleDemand(int depth) {
        Map<String, Long> x0 = Map.of("X0", 1L);
        Map<String, Long> x1 = Map.of("X1", 1L);
        for (int i = 2; i <= depth; i++) {
            var next = addDemand(x0, x1);
            x0 = x1;
            x1 = next;
        }
        return x1;
    }

    private static void addGreedyTrapMultiDag(List<ReferenceScenario> out, int amount) {
        var minimum = new LinkedHashMap<String, Long>();
        var starved = new LinkedHashMap<String, Long>();
        var allRMissing = new LinkedHashMap<String, Long>();
        var allSMissing = new LinkedHashMap<String, Long>();
        for (var group : GREEDY_TRAP_DATA) {
            minimum.put(group.r(), (long) amount);
            minimum.put(group.s(), (long) amount);
            starved.put(group.r(), (long) amount);
            allRMissing.put(group.r(), (long) amount);
            allSMissing.put(group.s(), (long) amount);
        }
        // Every group may be repaired with either R or S, so the full minimum frontier has 2^N
        // members. Two deterministic witnesses are enough for the cost baseline; refill replay
        // remains authoritative for every mixed report.
        List<Map<String, Long>> missing = List.of(
                Map.copyOf(allRMissing), Map.copyOf(allSMissing));
        addThreeModes(out, "multi-dag/greedy-trap", ReferenceCapability.MULTI_DAG, amount,
                GREEDY_TRAP_TARGET, amount, Map.copyOf(minimum), Map.copyOf(starved), missing,
                false,
                ThunderboltReferenceScenarios::greedyTrapMultiDag);
    }

    private static CraftGraph<String> greedyTrapMultiDag(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder();
        var groupOutputs = new ArrayList<CraftInput<String>>(GREEDY_TRAP_DATA.size());
        for (var group : GREEDY_TRAP_DATA) {
            builder.pattern(group.output(), 1, List.of(
                    CraftInput.of(group.a(), 1), CraftInput.of(group.b(), 1)));
            builder.pattern(group.a(), 1, List.of(CraftInput.of(group.r(), 1)));
            // R is deliberately registered before S: consuming it for B is a local greedy trap.
            builder.pattern(group.b(), 1, List.of(CraftInput.of(group.r(), 1)));
            builder.pattern(group.b(), 1, List.of(CraftInput.of(group.s(), 1)));
            groupOutputs.add(CraftInput.of(group.output(), 1));
        }
        builder.pattern(GREEDY_TRAP_TARGET, 1, groupOutputs);
        stock.forEach(builder::stock);
        return builder.build();
    }

    /**
     * Fixed-seed opaque material names give every trap independent String hash codes while keeping
     * failures exactly reproducible. This avoids one JVM choosing a single A/B order for the whole
     * benchmark merely because every batch reused the same two material keys.
     */
    private static List<GreedyTrapGroup> greedyTrapData() {
        var random = new SplittableRandom(GREEDY_TRAP_HASH_SEED);
        var used = new HashSet<String>();
        var groups = new ArrayList<GreedyTrapGroup>(GREEDY_TRAP_GROUPS);
        for (int index = 0; index < GREEDY_TRAP_GROUPS; index++) {
            groups.add(new GreedyTrapGroup(
                    randomMaterial(random, used),
                    randomMaterial(random, used),
                    randomMaterial(random, used),
                    randomMaterial(random, used),
                    randomMaterial(random, used)));
        }
        return List.copyOf(groups);
    }

    private static String randomMaterial(SplittableRandom random, Set<String> used) {
        String value;
        do {
            value = "g_" + Long.toUnsignedString(random.nextLong(), 36)
                    + "_" + Long.toUnsignedString(random.nextLong(), 36);
        } while (!used.add(value));
        return value;
    }

    private record GreedyTrapGroup(String a, String b, String r, String s, String output) {
    }

    private static void addFibonacciMultiDag(List<ReferenceScenario> out, int depth) {
        List<Map<String, Long>> frontier = fibonacciMultiMinimumFrontier(depth);
        Map<String, Long> minimum = frontier.getFirst();
        addThreeModes(out, "multi-dag/fibonacci", ReferenceCapability.MULTI_DAG, depth,
                "X" + depth, 1, minimum, Map.of(), frontier, false,
                stock -> fibonacciMultiDag(depth, stock));
    }

    private static CraftGraph<String> fibonacciMultiDag(int depth, Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder();
        for (int i = 3; i <= depth; i++) {
            builder.pattern("X" + i, 1, List.of(
                    CraftInput.of("X" + (i - 1), 1),
                    CraftInput.of("X" + (i - 2), 1)));
            builder.pattern("X" + i, 1, List.of(
                    CraftInput.of("X" + (i - 2), 1),
                    CraftInput.of("X" + (i - 3), 1)));
        }
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static List<Map<String, Long>> fibonacciMultiMinimumFrontier(int depth) {
        // Missing reports are validated by replay, so the frontier only needs one deterministic
        // minimum-cost witness for the overhead denominator. Retaining every tied vector grows
        // exponentially and would make the depth-32 reference generator the benchmark bottleneck.
        var options = new ArrayList<Map<String, Long>>();
        options.add(Map.of("X0", 1L));
        options.add(Map.of("X1", 1L));
        options.add(Map.of("X2", 1L));
        for (int i = 3; i <= depth; i++) {
            Map<String, Long> first = addDemand(options.get(i - 1), options.get(i - 2));
            Map<String, Long> second = addDemand(options.get(i - 2), options.get(i - 3));
            long firstCost = total(first);
            long secondCost = total(second);
            options.add(firstCost < secondCost
                    || (firstCost == secondCost && first.toString().compareTo(second.toString()) <= 0)
                            ? first
                            : second);
        }
        return List.of(options.get(depth));
    }

    private static void addConversionCycle(List<ReferenceScenario> out) {
        addThreeModes(out, "cycle/conversion-ring", ReferenceCapability.CYCLE_CUTTING, 3,
                "T", 1, Map.of("A", 2L), Map.of("A", 1L),
                List.of(Map.of("A", 1L), Map.of("C", 1L)),
                ThunderboltReferenceScenarios::conversionCycle);
    }

    private static CraftGraph<String> conversionCycle(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("C", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 9)))
                .pattern("B", 9, List.of(CraftInput.of("A", 1)))
                .pattern("B", 1, List.of(CraftInput.of("C", 9)))
                .pattern("C", 9, List.of(CraftInput.of("B", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addCatalyst(List<ReferenceScenario> out, int amount) {
        Map<String, Long> minimum = Map.of("A", 1L, "C", (long) amount);
        Map<String, Long> starved = Map.of("C", (long) amount);
        addThreeModes(out, "catalyst/returned-seed", ReferenceCapability.CATALYST, amount,
                "E", amount, minimum, starved, List.of(Map.of("A", 1L)),
                ThunderboltReferenceScenarios::catalyst);
    }

    /** Direct unchanged-catalyst notation: one ordinary network A is returned after every firing. */
    private static CraftGraph<String> catalyst(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("E", 1, List.of(
                        CraftInput.returned("A", 1),
                        CraftInput.of("C", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addRawCatalystLoop(List<ReferenceScenario> out, int amount) {
        Map<String, Long> minimum = Map.of("A", 1L, "C", (long) amount);
        Map<String, Long> starved = Map.of("C", (long) amount);
        addThreeModes(out, "catalyst/raw-feedback-loop", ReferenceCapability.CATALYST, amount,
                "E", amount, minimum, starved,
                List.of(Map.of("A", 1L), Map.of("D", 1L)),
                ThunderboltReferenceScenarios::rawCatalystLoop);
    }

    /** Ordinary balanced catalyst cycle: A->2B, 2B+C->E+D, D->A. */
    private static CraftGraph<String> rawCatalystLoop(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("B", 2, List.of(CraftInput.of("A", 1)))
                .pattern("E", 1,
                        List.of(CraftInput.of("B", 2), CraftInput.of("C", 1)),
                        List.of(CraftOutput.of("D", 1)))
                .pattern("A", 1, List.of(CraftInput.of("D", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addLossyFeedbackLoop(List<ReferenceScenario> out, int amount) {
        long minimumA = amount + 2L;
        addThreeModes(out, "catalyst/lossy-feedback-loop", ReferenceCapability.CATALYST, amount,
                "D", amount, Map.of("A", minimumA), Map.of("A", (long) amount),
                List.of(Map.of("A", 2L)), ThunderboltReferenceScenarios::lossyFeedbackLoop);
    }

    /** Ordinary decreasing feedback: each D consumes one A net and retains a two-A startup state. */
    private static CraftGraph<String> lossyFeedbackLoop(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("B", 2, List.of(CraftInput.of("A", 3)))
                .pattern("D", 1, List.of(CraftInput.of("B", 2)),
                        List.of(CraftOutput.of("A", 2)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addDurability(List<ReferenceScenario> out, int uses, int amount) {
        long tools = (amount + uses - 1L) / uses;
        Map<String, Long> minimum = Map.of("tool", tools, "raw", (long) amount);
        Map<String, Long> starved = Map.of("tool", tools - 1L, "raw", (long) amount);
        addThreeModes(out, "durability/finite-use-chain", ReferenceCapability.DURABILITY_CHAIN,
                uses, "product", amount, minimum, starved, List.of(Map.of("tool", 1L)),
                stock -> durability(uses, stock));
    }

    private static CraftGraph<String> durability(int uses, Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("product", 1, List.of(
                        CraftInput.of("raw", 1), CraftInput.finiteUse("tool", 1, uses)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addFuzzyVariant(List<ReferenceScenario> out, int amount) {
        var source = new ReusableStockSource("host", "fuzzy-reference");
        addScenario(out, "fuzzy/variant-route", ReferenceCapability.FUZZY_VARIANT,
                ReferenceMaterialMode.MISSING, amount, fuzzyVariant(source, Map.of()),
                "product", amount, false, List.of(Map.of("logical_tool", 1L)), true,
                supplied -> fuzzyVariant(source, supplied));
        addScenario(out, "fuzzy/variant-route", ReferenceCapability.FUZZY_VARIANT,
                ReferenceMaterialMode.MINIMUM, amount,
                fuzzyVariant(source, Map.of("damaged_tool", 1L)),
                "product", amount, true, List.of());
        addScenario(out, "fuzzy/variant-route", ReferenceCapability.FUZZY_VARIANT,
                ReferenceMaterialMode.UNBOUNDED, amount,
                fuzzyVariant(source, Map.of("damaged_tool", UNBOUNDED_STOCK)),
                "product", amount, true, List.of());
    }

    private static CraftGraph<String> fuzzyVariant(
            ReusableStockSource source, Map<String, Long> reusableStock) {
        var builder = CraftGraph.<String>builder()
                .pattern("product", 1, List.of(CraftInput.returnedFrom("logical_tool", 1, source)))
                .reusableStockRoute(source, "logical_tool", List.of("logical_tool", "damaged_tool"));
        reusableStock.forEach((key, amount) -> builder.reusableStock("host", key, amount));
        return builder.build();
    }

    private static void addThreeModes(
            List<ReferenceScenario> out,
            String id,
            ReferenceCapability capability,
            int scale,
            String target,
            long amount,
            Map<String, Long> minimumStock,
            Map<String, Long> starvedStock,
            List<Map<String, Long>> minimalMissing,
            GraphFactory factory) {
        addThreeModes(out, id, capability, scale, target, amount, minimumStock, starvedStock,
                minimalMissing, minimalMissing.size() == 1, factory);
    }

    private static void addThreeModes(
            List<ReferenceScenario> out,
            String id,
            ReferenceCapability capability,
            int scale,
            String target,
            long amount,
            Map<String, Long> minimumStock,
            Map<String, Long> starvedStock,
            List<Map<String, Long>> minimalMissing,
            boolean uniqueMinimalMissing,
            GraphFactory factory) {
        addScenario(out, id, capability, ReferenceMaterialMode.MISSING, scale,
                factory.build(starvedStock), target, amount, false, minimalMissing,
                uniqueMinimalMissing,
                supplied -> factory.build(mergedStock(starvedStock, supplied)));
        addScenario(out, id, capability, ReferenceMaterialMode.MINIMUM, scale,
                factory.build(minimumStock), target, amount, true, List.of());
        var unbounded = new HashMap<String, Long>();
        minimumStock.keySet().forEach(key -> unbounded.put(key, UNBOUNDED_STOCK));
        addScenario(out, id, capability, ReferenceMaterialMode.UNBOUNDED, scale,
                factory.build(unbounded), target, amount, true, List.of());
    }

    private static void addScenario(
            List<ReferenceScenario> out,
            String id,
            ReferenceCapability capability,
            ReferenceMaterialMode mode,
            int scale,
            CraftGraph<String> graph,
            String target,
            long amount,
            boolean feasible,
            List<Map<String, Long>> missing) {
        addScenario(out, id, capability, mode, scale, graph, target, amount, feasible, missing,
                missing.size() == 1, graph::withAdditionalStock);
    }

    private static void addScenario(
            List<ReferenceScenario> out,
            String id,
            ReferenceCapability capability,
            ReferenceMaterialMode mode,
            int scale,
            CraftGraph<String> graph,
            String target,
            long amount,
            boolean feasible,
            List<Map<String, Long>> missing,
            boolean uniqueMinimalMissing,
            java.util.function.Function<Map<String, Long>, CraftGraph<String>> refillGraph) {
        out.add(new ReferenceScenario(
                id + "/" + mode.name().toLowerCase(), capability, mode, scale,
                graph, target, amount, feasible, missing, Map.of(),
                uniqueMinimalMissing, refillGraph));
    }

    private static Map<String, Long> mergedStock(
            Map<String, Long> stock, Map<String, Long> additions) {
        var merged = new HashMap<>(stock);
        additions.forEach((key, amount) -> merged.merge(
                key, amount, com.moakiee.thunderbolt.core.crafting.planner.Sat::add));
        return Map.copyOf(merged);
    }

    private static Map<String, Long> addDemand(Map<String, Long> left, Map<String, Long> right) {
        var result = new LinkedHashMap<String, Long>();
        left.forEach((key, value) -> result.merge(key, value, Math::addExact));
        right.forEach((key, value) -> result.merge(key, value, Math::addExact));
        return Map.copyOf(result);
    }

    private static long total(Map<String, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    @FunctionalInterface
    private interface GraphFactory {
        CraftGraph<String> build(Map<String, Long> stock);
    }
}
