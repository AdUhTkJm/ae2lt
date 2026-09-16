package com.moakiee.thunderbolt.core.crafting.planner.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlan;

/** One graph, inventory mode and expected semantic result. */
public record ReferenceScenario(
        String id,
        ReferenceCapability capability,
        ReferenceMaterialMode materialMode,
        int scale,
        CraftGraph<String> graph,
        String target,
        long amount,
        boolean expectedFeasible,
        List<Map<String, Long>> minimalMissing,
        Map<String, Double> missingWeights,
        boolean uniqueMinimalMissing,
        Function<Map<String, Long>, CraftGraph<String>> refillGraph) {

    public ReferenceScenario(
            String id,
            ReferenceCapability capability,
            ReferenceMaterialMode materialMode,
            int scale,
            CraftGraph<String> graph,
            String target,
            long amount,
            boolean expectedFeasible,
            List<Map<String, Long>> minimalMissing,
            Map<String, Double> missingWeights) {
        this(id, capability, materialMode, scale, graph, target, amount, expectedFeasible,
                minimalMissing, missingWeights,
                minimalMissing != null && minimalMissing.size() == 1,
                additions -> graph.withAdditionalStock(additions));
    }

    public ReferenceScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(materialMode, "materialMode");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(refillGraph, "refillGraph");
        if (scale < 0 || amount <= 0) {
            throw new IllegalArgumentException("scale must be non-negative and amount positive");
        }
        var normalizedMissing = new ArrayList<Map<String, Long>>();
        if (minimalMissing != null) {
            for (var candidate : minimalMissing) {
                var clean = candidate.entrySet().stream()
                        .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, Map.Entry::getValue));
                normalizedMissing.add(clean);
            }
        }
        minimalMissing = List.copyOf(normalizedMissing);
        missingWeights = missingWeights == null ? Map.of() : Map.copyOf(missingWeights);
        if (expectedFeasible && !minimalMissing.isEmpty()) {
            throw new IllegalArgumentException("feasible scenarios cannot define missing baselines");
        }
        if (!expectedFeasible && minimalMissing.isEmpty()) {
            throw new IllegalArgumentException("infeasible scenarios need a missing baseline");
        }
    }

    Validation validate(CraftPlan<String> plan) {
        if (plan == null || !plan.supported()) {
            return Validation.declined();
        }
        if (expectedFeasible) {
            if (!plan.feasible() || hasPositiveMissing(plan.missing())) {
                return Validation.falseNegative();
            }
            return ReferencePlanReplay.completes(this, plan, false)
                    ? Validation.supported(1.0D)
                    : Validation.falsePositive();
        }

        if (plan.feasible() && !hasPositiveMissing(plan.missing())) {
            return ReferencePlanReplay.completes(this, plan, false)
                    ? Validation.supported(1.0D)
                    : Validation.falsePositive();
        }
        if (!hasPositiveMissing(plan.missing())) {
            return Validation.declined();
        }
        Map<String, Long> reported = positive(plan.missing());
        double minimumCost = minimalMissing.stream()
                .mapToDouble(this::weightedCost)
                .min()
                .orElseThrow();
        double reportedCost = weightedCost(reported);
        double overhead = reportedCost / minimumCost;
        if (!uniqueMinimalMissing && reportedCost <= Math.nextUp(minimumCost)) {
            // A report at the global minimum is itself another minimum witness; the runner separately
            // verifies that supplying it makes production planning executable. Non-unique scenarios
            // need not match the arbitrary representative retained by the bounded generator.
            return Validation.supported(overhead);
        }
        long reportedTotal = total(reported);
        long matchingMinimum = minimalMissing.stream()
                .filter(candidate -> candidate.keySet().equals(reported.keySet()))
                .mapToLong(ReferenceScenario::total)
                .min()
                .orElse(Long.MAX_VALUE);
        if (matchingMinimum != Long.MAX_VALUE && reportedTotal >= matchingMinimum) {
            return Validation.supported(overhead);
        }
        return uniqueMinimalMissing
                ? Validation.partiallySupported(overhead)
                : Validation.unknown();
    }

    private static boolean hasPositiveMissing(Map<String, Long> missing) {
        return missing.values().stream().anyMatch(value -> value != null && value > 0);
    }

    private static Map<String, Long> positive(Map<String, Long> missing) {
        return missing.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getValue() != null && entry.getValue() > 0)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue));
    }

    ReferenceScenario refilled(Map<String, Long> reportedMissing) {
        Map<String, Long> supplied = positive(reportedMissing);
        return new ReferenceScenario(
                id + "/refill", capability, materialMode, scale,
                refillGraph.apply(supplied), target, amount, true, List.of(), missingWeights,
                false, refillGraph);
    }

    private double weightedCost(Map<String, Long> missing) {
        double total = 0.0D;
        for (var entry : missing.entrySet()) {
            if (entry.getValue() > 0) {
                total += entry.getValue() * missingWeights.getOrDefault(entry.getKey(), 1.0D);
            }
        }
        return total;
    }

    private static long total(Map<String, Long> amounts) {
        long total = 0L;
        for (long amount : amounts.values()) {
            total = com.moakiee.thunderbolt.core.crafting.planner.Sat.add(total, amount);
        }
        return total;
    }

    record Validation(ReferenceSupportStatus status, double missingOverhead) {
        static Validation supported(double overhead) {
            return new Validation(ReferenceSupportStatus.SUPPORTED, overhead);
        }

        static Validation partiallySupported(double overhead) {
            return new Validation(ReferenceSupportStatus.PARTIALLY_SUPPORTED, overhead);
        }

        static Validation unknown() {
            return new Validation(ReferenceSupportStatus.UNKNOWN, Double.NaN);
        }

        static Validation declined() {
            return new Validation(ReferenceSupportStatus.ATTEMPT_DECLINED, Double.NaN);
        }

        static Validation falseNegative() {
            return new Validation(ReferenceSupportStatus.FALSE_NEGATIVE, Double.NaN);
        }

        static Validation falsePositive() {
            return new Validation(ReferenceSupportStatus.FALSE_POSITIVE, Double.NaN);
        }
    }
}
