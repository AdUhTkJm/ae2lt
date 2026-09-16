package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Planner-level port of the 37 cases in AE2-VM's
 * {@code Ae2VmBoundaryCapabilitySuiteTest} at {@code main@b03afe75}.
 *
 * <p>The source suite validates feasibility, not one implementation-specific firing order. Fuzzy
 * AE2 inputs are represented here by the same concrete recipe alternatives that
 * {@link FastCraftingPlanner} exports to the core planner; adapter-side candidate discovery remains
 * covered by {@code FastCraftingPlannerIdOnlyCraftableVariantTest}.
 */
class ExternalPlannerBoundaryCompatibilityTest {
    private static final Duration CASE_TIMEOUT = Duration.ofSeconds(1);

    private record BoundaryCase(
            String id,
            String target,
            long amount,
            boolean expectedFeasible,
            Supplier<CraftGraph<String>> graph) {
    }

    @TestFactory
    Stream<DynamicTest> ae2VmBoundaryCases() {
        List<BoundaryCase> cases = cases();
        var completed = new AtomicInteger();
        var tests = new ArrayList<DynamicTest>(cases.size() + 1);
        for (BoundaryCase boundaryCase : cases) {
            tests.add(DynamicTest.dynamicTest(boundaryCase.id(), () -> {
                long started = System.nanoTime();
                CraftPlan<String> plan = assertTimeoutPreemptively(
                        CASE_TIMEOUT,
                        () -> CraftPlannerV2.plan(
                                boundaryCase.graph().get(),
                                boundaryCase.target(),
                                boundaryCase.amount()));
                assertTrue(plan.supported(), "Thunderbolt must handle the imported planner case");
                assertEquals(
                        boundaryCase.expectedFeasible(),
                        plan.feasible(),
                        () -> boundaryCase.id() + " missing=" + plan.missing()
                                + " usedStock=" + plan.usedStock());
                completed.incrementAndGet();
                System.out.println("[external-planner-boundary] engine=thunderbolt id="
                        + boundaryCase.id()
                        + " feasible=" + plan.feasible()
                        + " missing=" + plan.missing()
                        + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000.0D);
            }));
        }
        tests.add(DynamicTest.dynamicTest("boundary-summary", () -> {
            assertEquals(37, cases.size());
            assertEquals(cases.size(), completed.get(), "every imported case must execute");
            System.out.println("[external-planner-boundary] engine=thunderbolt SUMMARY cases="
                    + completed.get());
        }));
        return tests.stream();
    }

    private static List<BoundaryCase> cases() {
        var cases = new ArrayList<BoundaryCase>();
        for (long amount : new long[] {1L, 2L, 100L}) {
            cases.add(fuzzyCase(
                    "quantity/craftable-primary-white-stock/" + amount,
                    amount, true, Map.of("white_wool", 1L, "black_wool", 1_000L)));
            cases.add(fuzzyCase(
                    "quantity/craftable-primary-white-stock10/" + amount,
                    amount, true, Map.of("white_wool", 10L, "black_wool", 1_000L)));
            cases.add(fuzzyCase(
                    "quantity/craftable-primary-partial-gray/" + amount,
                    amount, true, Map.of("gray_wool", 40L, "black_wool", 1_000L)));
            cases.add(fuzzyCase(
                    "quantity/craftable-primary-no-variant-stock/" + amount,
                    amount, true, Map.of("black_wool", 1_000L)));
            cases.add(new BoundaryCase(
                    "quantity/fuzzy-leaf-white-stock/" + amount,
                    "product", amount, true,
                    () -> fuzzyGraph(false, Map.of("white_wool", 1_000L))));
        }

        for (int levels : new int[] {10, 20}) {
            for (long midStock : new long[] {0L, 1L, 5L}) {
                for (long amount : new long[] {1L, 2L, 100L}) {
                    int capturedLevels = levels;
                    long capturedMidStock = midStock;
                    cases.add(new BoundaryCase(
                            "quantity/deep-chain-mid-stock-l" + levels + "-s" + midStock
                                    + "/" + amount,
                            "N" + (levels - 1), amount, true,
                            () -> deepChainGraph(capturedLevels, capturedMidStock)));
                }
            }
        }

        for (long amount : new long[] {1L, 2L, 100L}) {
            cases.add(new BoundaryCase(
                    "quantity/craftable-fluid-partial/" + amount,
                    "blank_pattern", amount, true,
                    ExternalPlannerBoundaryCompatibilityTest::craftableFluidGraph));
        }

        cases.add(new BoundaryCase(
                "quantity/infeasible-no-variant-stock/1",
                "product", 1L, false,
                () -> fuzzyGraph(false, Map.of())));
        return List.copyOf(cases);
    }

    private static BoundaryCase fuzzyCase(
            String id, long amount, boolean expectedFeasible, Map<String, Long> stock) {
        return new BoundaryCase(
                id, "product", amount, expectedFeasible, () -> fuzzyGraph(true, stock));
    }

    private static CraftGraph<String> fuzzyGraph(
            boolean grayCraftable, Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("product", 1, List.of(CraftInput.of("gray_wool", 1)))
                .pattern("product", 1, List.of(CraftInput.of("white_wool", 1)));
        if (grayCraftable) {
            builder.pattern("gray_wool", 1, List.of(CraftInput.of("black_wool", 1)));
        }
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static CraftGraph<String> deepChainGraph(int levels, long midStock) {
        var builder = CraftGraph.<String>builder();
        for (int index = 1; index < levels; index++) {
            builder.pattern(
                    "N" + index,
                    1,
                    List.of(CraftInput.of("N" + (index - 1), 1)));
        }
        builder.stock("N0", 1_000_000L);
        builder.stock("N" + (levels - 2), midStock);
        return builder.build();
    }

    private static CraftGraph<String> craftableFluidGraph() {
        return CraftGraph.<String>builder()
                .pattern("blank_pattern", 1, List.of(
                        CraftInput.of("circuit_board", 1),
                        CraftInput.of("fluid_x", 1_000L)))
                .pattern("circuit_board", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("fluid_x", 1_000L, List.of(CraftInput.of("water", 1)))
                .stock("raw", 1_000_000L)
                .stock("water", 1_000_000L)
                .stock("fluid_x", 500L)
                .build();
    }
}
