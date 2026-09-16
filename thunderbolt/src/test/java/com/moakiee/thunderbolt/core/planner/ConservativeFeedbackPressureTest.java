package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pressure boundary for the weighted place-potential proof, excluding JVM/Gradle startup. */
class ConservativeFeedbackPressureTest {
    private static final int MAX_STATES = 32;
    private static final int MAX_PATTERNS = 64;
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 9;
    private static final long LIMIT_NANOS = Duration.ofMillis(200).toNanos();
    private static final long HARD_LIMIT_NANOS = Duration.ofSeconds(3).toNanos();

    @Test
    void maximumAdmittedPrimeRatioSccStaysBelowTwoHundredMilliseconds() {
        Fixture fixture = primeRatioRing(MAX_STATES, MAX_PATTERNS, 0L);

        for (int warmup = 0; warmup < WARMUPS; warmup++) {
            assertCertified(fixture);
        }
        long[] elapsed = new long[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            long started = System.nanoTime();
            assertCertified(fixture);
            elapsed[sample] = System.nanoTime() - started;
        }
        Arrays.sort(elapsed);

        long p50 = elapsed[SAMPLES / 2];
        long p95 = elapsed[SAMPLES - 2];
        long maximum = elapsed[SAMPLES - 1];
        System.out.printf(
                "[potential-pressure] states=%d patterns=%d p50=%.3fms p95=%.3fms max=%.3fms%n",
                MAX_STATES,
                MAX_PATTERNS,
                p50 / 1_000_000.0D,
                p95 / 1_000_000.0D,
                maximum / 1_000_000.0D);
        assertTrue(maximum < LIMIT_NANOS,
                () -> "maximum weighted SCC proof took " + maximum / 1_000_000.0D + " ms");
    }

    @Test
    void longScaleCoefficientsShareTheWholePlanningAttemptDeadline() {
        Fixture fixture = primeRatioRing(MAX_STATES, MAX_PATTERNS, 1_000_000_000L);

        long started = System.nanoTime();
        long deadline = started + HARD_LIMIT_NANOS;
        var context = new com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext() {
            @Override
            public long deadlineNanos() {
                return deadline;
            }

            @Override
            public void checkpoint() {
                if (System.nanoTime() >= deadline) {
                    throw new com.moakiee.thunderbolt.api.crafting.PlanningExitException(
                            "pressure attempt exhausted");
                }
            }

            @Override
            public void report(
                    com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot snapshot) {
            }
        };
        ConservativeFeedbackAnalysis.Analysis<String> analysis;
        try (var ignored = PlanningCancellation.bind(context)) {
            analysis = ConservativeFeedbackAnalysis.analyzeAll(
                    fixture.states(), fixture.patternsByOutput());
        }
        long elapsed = System.nanoTime() - started;

        // The numeric stress case may conservatively decline on the structural work budget. It may
        // use the attempt's remaining wall clock, but it must not create a fresh per-SCC allowance.
        assertTrue(analysis.components().isEmpty());
        assertTrue(analysis.fallbacks().size() <= 1);
        System.out.printf(
                "[potential-pressure-long] certified=%s elapsed=%.3fms%n",
                !analysis.fallbacks().isEmpty(), elapsed / 1_000_000.0D);
        assertTrue(elapsed < HARD_LIMIT_NANOS,
                () -> "long-scale SCC proof returned after " + elapsed / 1_000_000.0D + " ms");
    }

    @Test
    void shapesBeyondEitherCalibratedDimensionAreDeclinedBeforeSearch() {
        Fixture tooManyStates = primeRatioRing(
                MAX_STATES + 1, 2 * (MAX_STATES + 1), 0L);
        Fixture tooManyPatterns = primeRatioRing(MAX_STATES, MAX_PATTERNS + 1, 0L);

        long started = System.nanoTime();
        var stateAnalysis = ConservativeFeedbackAnalysis.analyzeAll(
                tooManyStates.states(), tooManyStates.patternsByOutput());
        var patternAnalysis = ConservativeFeedbackAnalysis.analyzeAll(
                tooManyPatterns.states(), tooManyPatterns.patternsByOutput());
        long elapsed = System.nanoTime() - started;

        assertTrue(stateAnalysis.fallbacks().isEmpty());
        assertTrue(patternAnalysis.fallbacks().isEmpty());
        assertTrue(elapsed < LIMIT_NANOS,
                () -> "shape admission took " + elapsed / 1_000_000.0D + " ms");
    }

    private static void assertCertified(Fixture fixture) {
        var analysis = ConservativeFeedbackAnalysis.analyzeAll(
                fixture.states(), fixture.patternsByOutput());
        assertEquals(0, analysis.components().size());
        assertEquals(1, analysis.fallbacks().size());
        var fallback = analysis.fallbacks().get(0);
        assertEquals(MAX_STATES, fallback.states().size());
        assertEquals(MAX_PATTERNS, fallback.patterns().size());
        assertEquals(MAX_STATES, fallback.placePotential().size());
    }

    /**
     * Bidirectional prime-ratio edges force a non-unit rational relaxation. Every state has two
     * producers and two consumers, so the strict marked-cycle shortcut cannot accept the graph and
     * the general place-potential integer proof must run.
     */
    private static Fixture primeRatioRing(
            int stateCount, int patternCount, long weightBase) {
        if (patternCount < 2 * stateCount) {
            throw new IllegalArgumentException("a bidirectional ring needs two patterns per state");
        }
        long[] weights = primes(stateCount, weightBase);
        List<String> states = new ArrayList<>(stateCount);
        for (int state = 0; state < stateCount; state++) states.add("S" + state);

        Map<String, List<CraftPattern<String>>> patternsByOutput = new LinkedHashMap<>();
        int patterns = 0;
        for (int step = 1; patterns < patternCount; step++) {
            for (int current = 0; current < stateCount && patterns < patternCount; current++) {
                int next = (current + step) % stateCount;
                if (next == current) continue;
                CraftPattern<String> forward = ratioPattern(
                        states.get(current), weights[next],
                        states.get(next), weights[current], patterns++);
                patternsByOutput.computeIfAbsent(forward.output(), ignored -> new ArrayList<>())
                        .add(forward);
                if (patterns >= patternCount) break;
                CraftPattern<String> reverse = ratioPattern(
                        states.get(next), weights[current],
                        states.get(current), weights[next], patterns++);
                patternsByOutput.computeIfAbsent(reverse.output(), ignored -> new ArrayList<>())
                        .add(reverse);
            }
        }
        return new Fixture(List.copyOf(states), Map.copyOf(patternsByOutput));
    }

    private static CraftPattern<String> ratioPattern(
            String input, long inputAmount, String output, long outputAmount, int index) {
        return new CraftPattern<>(
                output, outputAmount, List.of(CraftInput.of(input, inputAmount)), "ratio-" + index);
    }

    private static long[] primes(int count, long base) {
        long[] result = new long[count];
        int found = 0;
        for (int candidate = 2; found < count; candidate++) {
            boolean prime = true;
            for (int divisor = 2; divisor * divisor <= candidate; divisor++) {
                if (candidate % divisor == 0) {
                    prime = false;
                    break;
                }
            }
            if (prime) result[found++] = Math.addExact(base, candidate);
        }
        return result;
    }

    private record Fixture(
            List<String> states, Map<String, List<CraftPattern<String>>> patternsByOutput) {
    }
}
