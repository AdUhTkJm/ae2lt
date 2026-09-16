package com.moakiee.thunderbolt.core.crafting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PlannerPackageBoundaryTest {
    @Test
    void supportPackageDoesNotDependOnPlannerImplementation() throws Exception {
        var support = Path.of("src/main/java/com/moakiee/thunderbolt/core/crafting/support");
        try (var files = Files.walk(support)) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var source = Files.readString(file);
                assertFalse(
                        source.contains("com.moakiee.thunderbolt.core.crafting.planner"),
                        () -> "support must not import planner implementation: " + file);
            }
        }
    }

    @Test
    void apiAndCoreDoNotDependOnImplementationMixins() throws Exception {
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/api"),
                "com.moakiee.thunderbolt.core",
                "API must not import core implementation");
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/api"),
                "com.moakiee.thunderbolt.mixin",
                "API must not import mixin implementation");
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/core"),
                "com.moakiee.thunderbolt.mixin",
                "core must not import mixin implementation");
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/ae2"),
                "com.moakiee.thunderbolt.mixin",
                "AE2 adapters outside the mixin layer must not import mixin implementation");
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/compat"),
                "com.moakiee.thunderbolt.mixin",
                "compat adapters outside the mixin layer must not import mixin implementation");
    }

    @Test
    void craftingCalculationKeepsVanillaNativeAndIsolatesEngines() throws Exception {
        var source = Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/mixin/ae2/crafting/"
                        + "CraftingCalculationMixin.java"));

        int vanillaBranch = source.indexOf(
                "if (choice.kind() == PlanningChoice.Kind.VANILLA)");
        int isolatedExecution = source.indexOf("PlanningCandidateExecutor.execute(");
        assertTrue(vanillaBranch >= 0);
        assertTrue(source.contains("ICraftingPlan result = original.call(instance);"));
        assertTrue(isolatedExecution > vanillaBranch,
                "vanilla must return through AE2 before engine isolation begins");
        var vanillaSource = source.substring(vanillaBranch, isolatedExecution);
        assertTrue(vanillaSource.contains("if (result == null)"));
        assertTrue(vanillaSource.contains("thunderbolt$declinedEngines.incrementAndGet();"));
        assertTrue(vanillaSource.contains("break;"),
                "a declined terminal vanilla candidate must reach ALL_FAILED");
        assertFalse(vanillaSource.contains("throw new PlanningCandidateDeclinedException()"));
        assertFalse(source.contains("thunderbolt$runVanillaCandidate"));
        assertTrue(source.contains("try (session)"));
        assertFalse(source.contains("PlanningAttemptMonitor.start("));
    }

    @Test
    void candidateWaitingChecksOnceAndHandsTheTickBackImmediately() throws Exception {
        var executor = Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/core/crafting/algorithm/"
                        + "PlanningCandidateExecutor.java"));
        var mixin = Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/mixin/ae2/crafting/"
                        + "CraftingCalculationMixin.java"));

        assertTrue(executor.contains("if (!result.isDone())"));
        assertFalse(executor.contains("POLL_MS"));
        assertFalse(executor.contains("get(POLL_MS"));
        assertTrue(mixin.contains("this::thunderbolt$pauseUntilNextTick"));
        assertTrue(mixin.contains("running = false;"));
        assertTrue(mixin.contains("monitor.notify();"));
        assertTrue(mixin.contains("while (!running)"));
        assertFalse(mixin.contains("handlePausing()"));
        assertFalse(executor.contains("ThreadLocal<"));
        assertTrue(mixin.contains("method = \"handlePausing\""));
        assertTrue(mixin.contains("PlanningCandidateExecutor.checkpointCandidateThread()"));
        assertTrue(executor.contains("PlanningCancellation.checkpointIfBound()"));
        assertTrue(mixin.contains("ci.cancel()"));
    }

    @Test
    void mixinBridgeLivesOutsideTheDeclaredMixinPackage() {
        var source = Path.of("src/main/java/com/moakiee/thunderbolt");
        assertTrue(Files.exists(source.resolve("ae2/crafting/CraftingPlanningControl.java")));
        assertTrue(Files.exists(source.resolve("ae2/crafting/CapturedPlanningChoice.java")));
        assertFalse(Files.exists(source.resolve("mixin/ae2/crafting/CraftingPlanningControl.java")));
        assertFalse(Files.exists(source.resolve("mixin/ae2/crafting/CapturedPlanningChoice.java")));
    }

    @Test
    void declaredMixinPackageContainsOnlyMixinsAndConfigInfrastructure() throws Exception {
        var mixin = Path.of("src/main/java/com/moakiee/thunderbolt/mixin");
        try (var files = Files.walk(mixin)) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var name = file.getFileName().toString();
                if (name.equals("OptionalMixinSelector.java")
                        || name.equals("ThunderboltMixinConfigPlugin.java")) {
                    continue;
                }
                var source = Files.readString(file);
                assertTrue(source.contains("@Mixin"),
                        () -> "ordinary runtime helper must live outside the declared Mixin package: " + file);
            }
        }
    }

    @Test
    void v2EngineLivesWithThePlannerItAdapts() {
        var crafting = Path.of("src/main/java/com/moakiee/thunderbolt/core/crafting");
        assertTrue(Files.exists(crafting.resolve("planner/ThunderboltV2PlanningEngine.java")));
        assertFalse(Files.exists(crafting.resolve("algorithm/ThunderboltV2PlanningEngine.java")));
    }

    @Test
    void cpSatEngineIsNotAV2IntegerBackend() throws Exception {
        var planner = Path.of("src/main/java/com/moakiee/thunderbolt/core/crafting/planner");
        var engine = Files.readString(planner.resolve("CpSatPlanningEngine.java"));
        var solver = Files.readString(planner.resolve("CpSatRankedFlowSolver.java"));
        var runtime = Files.readString(planner.resolve("CpSatRuntime.java"));

        assertTrue(engine.contains("FastCraftingPlanner.CalculationSession.cpSat()"));
        assertFalse(engine.contains("CraftPlannerV2"));
        assertFalse(solver.contains("CraftPlannerV2"));
        assertFalse(runtime.contains("CraftPlannerV2"));
        assertFalse(solver.contains("IntegerFlowBackend"));
    }

    private static void assertSourcesDoNotContain(
            Path root, String forbidden, String message) throws Exception {
        try (var files = Files.walk(root)) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var source = Files.readString(file);
                assertFalse(source.contains(forbidden), () -> message + ": " + file);
            }
        }
    }
}
