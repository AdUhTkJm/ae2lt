package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;

import com.moakiee.thunderbolt.core.crafting.loop.CraftingCpuRestrictedPattern;
import com.moakiee.thunderbolt.core.crafting.plan.LoopCraftingPlan;

import org.junit.jupiter.api.Test;

class CraftingPlanSummaryAdapterTest {

    @Test
    void preservesNativeCraftingPlanIdentity() {
        var nativePlan = plan(12, true, false);
        assertSame(nativePlan, CraftingPlanSummaryAdapter.adapt(nativePlan));
    }

    @Test
    void exposesLoopDelegateWithoutReplacingTheLoopPlan() {
        var delegate = plan(24, false, true);
        CraftingCpuRestrictedPattern restriction = ignored -> true;
        var loopPlan = new LoopCraftingPlan(
                delegate, List.of(restriction), Map.of(), Map.of(), List.of());

        assertSame(delegate, CraftingPlanSummaryAdapter.adapt(loopPlan));
    }

    @Test
    void snapshotsArbitraryThirdPartyPlanAsConcreteAe2Plan() {
        var used = new KeyCounter();
        var emitted = new KeyCounter();
        var missing = new KeyCounter();
        ICraftingPlan thirdPartyPlan = new ThirdPartyPlan(
                null, 48, true, true, used, emitted, missing, Map.of());

        CraftingPlan adapted = CraftingPlanSummaryAdapter.adapt(thirdPartyPlan);

        assertEquals(48, adapted.bytes());
        assertEquals(true, adapted.simulation());
        assertEquals(true, adapted.multiplePaths());
        assertNotSame(thirdPartyPlan, adapted);
        assertNotSame(used, adapted.usedItems());
        assertNotSame(emitted, adapted.emittedItems());
        assertNotSame(missing, adapted.missingItems());
    }

    private static CraftingPlan plan(long bytes, boolean simulation, boolean multiplePaths) {
        return new CraftingPlan(
                null,
                bytes,
                simulation,
                multiplePaths,
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter(),
                Map.of());
    }

    private record ThirdPartyPlan(
            GenericStack finalOutput,
            long bytes,
            boolean simulation,
            boolean multiplePaths,
            KeyCounter usedItems,
            KeyCounter emittedItems,
            KeyCounter missingItems,
            Map<IPatternDetails, Long> patternTimes) implements ICraftingPlan {
    }
}
