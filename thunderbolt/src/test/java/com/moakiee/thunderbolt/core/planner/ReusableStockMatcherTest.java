package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ReusableStockMatcherTest {
    @Test
    void longMaxSharedCapacityNeverOverflowsIntoFalseFeasibility() {
        var a = new ReusableStockRouteKey<String>(
                new ReusableStockSource("host", "shared", "a"), "A");
        var b = new ReusableStockRouteKey<String>(
                new ReusableStockSource("host", "shared", "b"), "B");
        var result = ReusableStockMatcher.allocate(
                Map.of(new ReusableStockKey<>("host", "X"), Long.MAX_VALUE),
                Map.of(a, Long.MAX_VALUE, b, Long.MAX_VALUE),
                ignored -> List.of("X"));

        assertFalse(result.feasible());
    }

    @Test
    void longMaxIndependentVariantsRemainFeasible() {
        var a = new ReusableStockRouteKey<String>(
                new ReusableStockSource("host", "shared", "a"), "A");
        var b = new ReusableStockRouteKey<String>(
                new ReusableStockSource("host", "shared", "b"), "B");
        var result = ReusableStockMatcher.allocate(
                Map.of(
                        new ReusableStockKey<>("host", "X"), Long.MAX_VALUE,
                        new ReusableStockKey<>("host", "Y"), Long.MAX_VALUE),
                Map.of(a, Long.MAX_VALUE, b, Long.MAX_VALUE),
                route -> route.equals(a) ? List.of("X") : List.of("Y"));

        assertTrue(result.feasible());
    }

    @Test
    void largeFullyFuzzyLongMaxGraphMaterializesEachRouteOnce() {
        int size = 256;
        var available = new LinkedHashMap<ReusableStockKey<Integer>, Long>();
        var demand = new LinkedHashMap<ReusableStockRouteKey<Integer>, Long>();
        var variants = new ArrayList<Integer>(size);
        for (int i = 0; i < size; i++) {
            variants.add(i);
            available.put(new ReusableStockKey<>("host", i), Long.MAX_VALUE);
            demand.put(new ReusableStockRouteKey<>(
                    new ReusableStockSource("host", "shared", "route-" + i), i), Long.MAX_VALUE);
        }

        var candidateCalls = new AtomicInteger();
        var candidateVisits = new AtomicLong();
        Iterable<Integer> countingCandidates = () -> new Iterator<>() {
            private final Iterator<Integer> delegate = variants.iterator();

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public Integer next() {
                candidateVisits.incrementAndGet();
                return delegate.next();
            }
        };

        var result = assertTimeout(Duration.ofSeconds(10), () -> ReusableStockMatcher.allocate(
                available,
                demand,
                ignored -> {
                    candidateCalls.incrementAndGet();
                    return countingCandidates;
                }));

        assertTrue(result.feasible());
        assertEquals(size, candidateCalls.get());
        assertEquals((long) size * size, candidateVisits.get());
    }

    @Test
    void oversizedPotentialMatrixReturnsConservativeInfeasibleBeforeCandidateExpansion() {
        int size = 513;
        var available = new LinkedHashMap<ReusableStockKey<Integer>, Long>();
        var demand = new LinkedHashMap<ReusableStockRouteKey<Integer>, Long>();
        for (int i = 0; i < size; i++) {
            available.put(new ReusableStockKey<>("host", i), 1L);
            demand.put(new ReusableStockRouteKey<>(
                    new ReusableStockSource("host", "shared", "route-" + i), i), 1L);
        }
        var candidateCalls = new AtomicInteger();

        var result = assertTimeout(Duration.ofSeconds(1), () -> ReusableStockMatcher.allocate(
                available,
                demand,
                ignored -> {
                    candidateCalls.incrementAndGet();
                    return List.of();
                }));

        assertFalse(result.feasible());
        assertTrue(result.allocation().isEmpty());
        assertEquals(0, candidateCalls.get(),
                "the cartesian preflight must run before materializing route candidates");
    }
}
