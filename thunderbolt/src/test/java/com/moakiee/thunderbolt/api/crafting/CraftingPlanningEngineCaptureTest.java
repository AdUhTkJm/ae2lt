package com.moakiee.thunderbolt.api.crafting;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class CraftingPlanningEngineCaptureTest {
    @Test
    void captureDefaultsToNoAdditionalInput() {
        CraftingPlanningEngine engine = new CraftingPlanningEngine() {
            @Override
            public ResourceLocation id() {
                return ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "capture_default");
            }

            @Override
            public boolean check(
                    appeng.api.networking.IGrid grid, PlanningRequest request) {
                return true;
            }

            @Override
            public PlanningEngineSession createSession(
                    PlanningRequest request,
                    Object capturedInput,
                    PlanningAttemptContext context) {
                return null;
            }
        };

        assertNull(engine.capture(null, null));
    }
}
