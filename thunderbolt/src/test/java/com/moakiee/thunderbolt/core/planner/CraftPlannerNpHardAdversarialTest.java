package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CraftPlannerNpHardAdversarialTest {

    @Test
    void boundedUnitAndBatchExactCoverMatchBruteForceOracle() {
        Random random = new Random(0x33C0FEEL);
        int samples = 33;
        int elements = 9;
        int coverSize = elements / 3;
        int falsePositives = 0;
        int falseNegatives = 0;
        int oracleFeasible = 0;
        int plannerFeasible = 0;
        long totalNanos = 0;
        long maxNanos = 0;
        String firstMismatch = null;
        for (int sample = 0; sample < samples; sample++) {
            List<int[]> sets = randomTriples(random, elements, 12);
            boolean oracle = hasExactCover(sets, elements, coverSize);
            for (long pickOutputAmount : new long[] {1L, 2L}) {
                CraftGraph<String> graph = exactCoverGraph(
                        sets, elements, coverSize, pickOutputAmount);

                PlanningResult<String> result = CraftPlannerV2.planDetailed(graph, "target", 1);
                if (oracle) oracleFeasible++;
                if (result.plan().feasible()) plannerFeasible++;
                totalNanos += result.diagnostics().totalNanos();
                maxNanos = Math.max(maxNanos, result.diagnostics().totalNanos());
                if (!oracle && result.plan().feasible()) falsePositives++;
                if (oracle && !result.plan().feasible()) falseNegatives++;
                if (firstMismatch == null && oracle != result.plan().feasible()) {
                    firstMismatch = "sample=" + sample + " pickOutput=" + pickOutputAmount
                            + " oracle=" + oracle + " missing=" + result.plan().missing()
                            + " diagnostics=" + result.diagnostics();
                }
            }
        }
        int runs = samples * 2;
        assertEquals(0, falsePositives + falseNegatives,
                "falsePositives=" + falsePositives + " falseNegatives=" + falseNegatives
                        + " oracleFeasible=" + oracleFeasible
                        + " plannerFeasible=" + plannerFeasible
                        + " avgNanos=" + totalNanos / runs + " maxNanos=" + maxNanos
                        + " firstMismatch=" + firstMismatch);
    }

    @Test
    void boundedUnboundedSubsetSumNeverReturnsFalsePositiveAgainstDynamicProgrammingOracle() {
        Random random = new Random(0xC01C0DEL);
        int samples = 33;
        int falsePositives = 0;
        int falseNegatives = 0;
        int oracleFeasible = 0;
        int plannerFeasible = 0;
        long totalNanos = 0;
        long maxNanos = 0;
        String firstMismatch = null;
        for (int sample = 0; sample < samples; sample++) {
            int target = 400 + random.nextInt(1_600);
            int forcedDivisor = sample % 2 == 0 ? 2 + random.nextInt(4) : 1;
            while (target % forcedDivisor == 0 && forcedDivisor > 1) {
                target++;
            }
            Set<Integer> unique = new HashSet<>();
            while (unique.size() < 18) {
                int coefficient = 17 + random.nextInt(180);
                unique.add(forcedDivisor * coefficient);
            }
            List<Integer> coins = List.copyOf(unique);
            boolean oracle = unboundedSubsetSum(coins, target);
            CraftGraph.Builder<String> builder = CraftGraph.builder();
            for (int i = 0; i < coins.size(); i++) {
                int coin = coins.get(i);
                builder.pattern(new CraftPattern<>(
                        "target",
                        coin,
                        List.of(CraftInput.of("raw", coin)),
                        "coin-" + i + "-" + coin));
            }
            builder.stock("raw", target);

            PlanningResult<String> result = CraftPlannerV2.planDetailed(
                    builder.build(), "target", target);
            if (oracle) oracleFeasible++;
            if (result.plan().feasible()) plannerFeasible++;
            totalNanos += result.diagnostics().totalNanos();
            maxNanos = Math.max(maxNanos, result.diagnostics().totalNanos());
            if (!oracle && result.plan().feasible()) falsePositives++;
            if (oracle && !result.plan().feasible()) falseNegatives++;
            if (firstMismatch == null && oracle != result.plan().feasible()) {
                firstMismatch = "sample=" + sample + " target=" + target
                        + " oracle=" + oracle + " coins=" + coins
                        + " missing=" + result.plan().missing()
                        + " diagnostics=" + result.diagnostics();
            }
        }
        assertEquals(0, falsePositives,
                "falsePositives=" + falsePositives + " falseNegatives=" + falseNegatives
                        + " oracleFeasible=" + oracleFeasible
                        + " plannerFeasible=" + plannerFeasible
                        + " avgNanos=" + totalNanos / samples + " maxNanos=" + maxNanos
                        + " firstMismatch=" + firstMismatch);
    }

    @Test
    void plantedExactCoverBehindDecoysRemainsFeasible() {
        int coverSize = 6;
        int elements = 3 * coverSize;
        CraftGraph.Builder<String> builder = CraftGraph.builder();

        // These routes all overlap on E0/E1 and therefore cannot form an exact cover. Keeping them
        // first makes a greedy split look plausible while hiding the planted cover behind it.
        for (int i = 0; i < 12; i++) {
            addSet(builder, "decoy-" + i, 0, 1, 2 + i % (elements - 2));
        }
        for (int i = 0; i < coverSize; i++) {
            addSet(builder, "cover-" + i, 3 * i, 3 * i + 1, 3 * i + 2);
        }

        List<CraftInput<String>> targetInputs = new ArrayList<>();
        targetInputs.add(CraftInput.of("pick", coverSize));
        for (int i = 0; i < elements; i++) {
            targetInputs.add(CraftInput.of("E" + i, 1));
        }
        builder.pattern("target", 1, targetInputs);

        PlanningResult<String> result = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> CraftPlannerV2.planDetailed(builder.build(), "target", 1));

        assertTrue(result.plan().feasible(), () -> "planted exact cover was missed: missing="
                + result.plan().missing() + " diagnostics=" + result.diagnostics());
        assertEquals(36, result.diagnostics().separatorWidthPeak());
        assertEquals(1, result.diagnostics().lowWidthSolved());
        assertEquals(0, result.diagnostics().lowWidthCutoffs());
    }

    @Test
    void largePlantedExactCoverBehindHundredsOfDecoysRemainsBounded() {
        int coverSize = 40;
        int elements = 3 * coverSize;
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (int i = 0; i < 360; i++) {
            addSet(builder, "large-decoy-" + i, 0, 1, 2 + i % (elements - 2));
        }
        for (int i = 0; i < coverSize; i++) {
            addSet(builder, "large-cover-" + i, 3 * i, 3 * i + 1, 3 * i + 2);
        }

        List<CraftInput<String>> targetInputs = new ArrayList<>();
        targetInputs.add(CraftInput.of("pick", coverSize));
        for (int i = 0; i < elements; i++) {
            targetInputs.add(CraftInput.of("E" + i, 1));
        }
        builder.pattern("target", 1, targetInputs);

        assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> CraftPlannerV2.planDetailed(builder.build(), "target", 1));
    }

    private static void addSet(
            CraftGraph.Builder<String> builder, String id, int first, int second, int third) {
        addSet(builder, id, 1L, first, second, third);
    }

    private static void addSet(
            CraftGraph.Builder<String> builder,
            String id,
            long pickOutputAmount,
            int first,
            int second,
            int third) {
        builder.pattern(new CraftPattern<>(
                        "pick",
                        pickOutputAmount,
                        List.of(CraftInput.of("ticket-" + id, 1)),
                        List.of(
                                CraftOutput.of("E" + first, 1),
                                CraftOutput.of("E" + second, 1),
                                CraftOutput.of("E" + third, 1)),
                        id))
                .stock("ticket-" + id, 1);
    }

    private static CraftGraph<String> exactCoverGraph(
            List<int[]> sets, int elements, int coverSize, long pickOutputAmount) {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (int i = 0; i < sets.size(); i++) {
            int[] set = sets.get(i);
            addSet(builder, "set-" + i, pickOutputAmount, set[0], set[1], set[2]);
        }
        List<CraftInput<String>> targetInputs = new ArrayList<>();
        targetInputs.add(CraftInput.of("pick", coverSize));
        for (int i = 0; i < elements; i++) {
            targetInputs.add(CraftInput.of("E" + i, 1));
        }
        return builder.pattern("target", 1, targetInputs).build();
    }

    private static List<int[]> randomTriples(Random random, int elements, int count) {
        Set<String> seen = new HashSet<>();
        List<int[]> result = new ArrayList<>();
        while (result.size() < count) {
            int a = random.nextInt(elements);
            int b = random.nextInt(elements);
            int c = random.nextInt(elements);
            if (a == b || a == c || b == c) continue;
            int[] triple = {a, b, c};
            java.util.Arrays.sort(triple);
            String key = triple[0] + ":" + triple[1] + ":" + triple[2];
            if (seen.add(key)) result.add(triple);
        }
        return result;
    }

    private static boolean hasExactCover(List<int[]> sets, int elements, int coverSize) {
        int all = (1 << elements) - 1;
        int combinations = 1 << sets.size();
        for (int selected = 0; selected < combinations; selected++) {
            if (Integer.bitCount(selected) != coverSize) continue;
            int covered = 0;
            boolean overlap = false;
            for (int i = 0; i < sets.size(); i++) {
                if ((selected & (1 << i)) == 0) continue;
                int[] set = sets.get(i);
                int mask = (1 << set[0]) | (1 << set[1]) | (1 << set[2]);
                if ((covered & mask) != 0) {
                    overlap = true;
                    break;
                }
                covered |= mask;
            }
            if (!overlap && covered == all) return true;
        }
        return false;
    }

    private static boolean unboundedSubsetSum(List<Integer> coins, int target) {
        boolean[] reachable = new boolean[target + 1];
        reachable[0] = true;
        for (int value = 1; value <= target; value++) {
            for (int coin : coins) {
                if (coin <= value && reachable[value - coin]) {
                    reachable[value] = true;
                    break;
                }
            }
        }
        return reachable[target];
    }
}
