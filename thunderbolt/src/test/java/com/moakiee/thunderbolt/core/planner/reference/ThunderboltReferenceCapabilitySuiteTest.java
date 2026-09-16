package com.moakiee.thunderbolt.core.crafting.planner.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.moakiee.thunderbolt.core.crafting.planner.CraftPlannerV2;

/** Runs Thunderbolt V2 itself through the public author reference scenarios. */
class ThunderboltReferenceCapabilitySuiteTest {
    /** Each check+plan pass gets 1s; the extra 100ms only observes cancellation after its cutoff. */
    private static final ReferenceCapabilityRunner RUNNER = new ReferenceCapabilityRunner(
            Duration.ofSeconds(1), Duration.ofMillis(100));
    /** Explicit current-engine limitations. They remain visible but do not make the report task fail. */
    private static final Map<String, ReferenceSupportStatus> EXPECTED_LIMITATIONS = Map.of();

    private static final ReferencePlanner THUNDERBOLT_V2 = new ReferencePlanner() {
        @Override
        public boolean check(ReferenceScenario scenario) {
            return true;
        }

        @Override
        public com.moakiee.thunderbolt.core.crafting.planner.CraftPlan<String> plan(
                ReferenceScenario scenario) {
            return CraftPlannerV2.plan(
                    scenario.graph(), scenario.target(), scenario.amount());
        }
    };

    @TestFactory
    Stream<DynamicTest> referenceCapabilities() {
        return ThunderboltReferenceScenarios.all().stream().map(scenario -> DynamicTest.dynamicTest(
                scenario.id(), () -> {
                    var result = RUNNER.run(THUNDERBOLT_V2, scenario);
                    System.out.println("[reference-capability] id=" + scenario.id()
                            + " capability=" + scenario.capability()
                            + " mode=" + scenario.materialMode()
                            + " scale=" + scenario.scale()
                            + " status=" + result.status()
                            + " missingOverhead=" + result.missingOverhead()
                            + " missing=" + (result.plan() == null ? null : result.plan().missing())
                            + " elapsedMs=" + result.elapsedNanos() / 1_000_000.0D);
                    var expected = EXPECTED_LIMITATIONS.getOrDefault(
                            scenario.id(), ReferenceSupportStatus.SUPPORTED);
                    assertEquals(expected, result.status(),
                            () -> "reference capability failed: " + scenario.id()
                                    + " status=" + result.status()
                                    + " failure=" + result.failure());
                }));
    }

}
