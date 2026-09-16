package com.moakiee.thunderbolt.mixin.ae2.crafting.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;

import com.moakiee.thunderbolt.core.crafting.pattern.CraftingStockPolicy;
import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPattern;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlan;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlannerV2;
import com.moakiee.thunderbolt.core.crafting.planner.FastCraftingPlanner;

import org.junit.jupiter.api.Test;

class FastCraftingPlannerPlanConversionTest {
    private static final AEKey A = new TestKey("a");
    private static final AEKey B = new TestKey("b");
    private static final AEKey C = new TestKey("c");
    private static final AEKey D = new TestKey("d");
    private static final AEKey E = new TestKey("e");
    private static final AEKey TARGET = new TestKey("target");

    @Test
    void unrelatedShortfallSharingAFuzzyCandidateSurvivesPlanConversion() throws Exception {
        IPatternDetails producesA = new FakePattern(A, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(C, 1))
        });
        IPatternDetails consumesFuzzy = new FakePattern(D, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(A, 1), new GenericStack(B, 1))
        });
        IPatternDetails consumesExactB = new FakePattern(E, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(B, 1))
        });
        IPatternDetails producesTarget = new FakePattern(TARGET, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(D, 1)),
                new FakeInput(new GenericStack(E, 1))
        });

        CraftPattern<AEKey> makeA = new CraftPattern<>(
                A, 1, List.of(CraftInput.of(C, 1)), producesA);
        CraftPattern<AEKey> makeDFromA = new CraftPattern<>(
                D, 1, List.of(CraftInput.of(A, 1)), consumesFuzzy);
        CraftPattern<AEKey> makeDFromB = new CraftPattern<>(
                D, 1, List.of(CraftInput.of(B, 1)), consumesFuzzy);
        CraftPattern<AEKey> makeE = new CraftPattern<>(
                E, 1, List.of(CraftInput.of(B, 1)), consumesExactB);
        CraftPattern<AEKey> makeTarget = new CraftPattern<>(
                TARGET, 1, List.of(CraftInput.of(D, 1), CraftInput.of(E, 1)), producesTarget);

        CraftPlan<AEKey> internal = CraftPlannerV2.plan(
                CraftGraph.<AEKey>builder()
                        .pattern(makeA)
                        .pattern(makeDFromA)
                        .pattern(makeDFromB)
                        .pattern(makeE)
                        .pattern(makeTarget)
                        .stock(C, 1)
                        .build(),
                TARGET,
                1);

        CraftingPlan exported = convert(internal);

        assertFalse(internal.feasible());
        assertEquals(1L, internal.missing().get(B));
        assertEquals(1L, internal.firings().get(makeA));
        assertEquals(1L, internal.firings().get(makeDFromA));
        assertEquals(0L, internal.firings().getOrDefault(makeDFromB, 0L));
        assertTrue(exported.simulation());
        assertFalse(exported.missingItems().isEmpty(),
                "the fuzzy D route must not hide B required independently by E");
        assertEquals(1L, exported.missingItems().get(B));
    }

    private static CraftingPlan convert(CraftPlan<AEKey> internal) throws Exception {
        Method method = FastCraftingPlanner.class.getDeclaredMethod(
                "toAe2Plan", AEKey.class, long.class, CraftPlan.class,
                boolean.class, boolean.class, Map.class, Map.class, Set.class,
                ChildCraftingSimulationState.class, CraftingStockPolicy.class);
        method.setAccessible(true);
        var snapshot = new ChildCraftingSimulationState(new EmptyInventory());
        return (CraftingPlan) method.invoke(
                null, TARGET, 1L, internal, true, true, Map.of(), Map.of(), Set.of(), snapshot, null);
    }

    private record FakePattern(AEKey output, IInput[] inputs) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(output, 1));
        }
    }

    private record FakeInput(GenericStack... possible) implements IPatternDetails.IInput {
        @Override public GenericStack[] getPossibleInputs() { return possible; }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey key, Level level) { return true; }
        @Override public AEKey getRemainingKey(AEKey key) { return null; }
    }

    private static final class EmptyInventory implements ICraftingInventory {
        @Override public void insert(AEKey key, long amount, Actionable mode) { }
        @Override public long extract(AEKey key, long amount, Actionable mode) { return 0; }
        @Override public Iterable<AEKey> findFuzzyTemplates(AEKey key) {
            return Collections.emptyList();
        }
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String id;

        private TestKey(String id) {
            this.id = id;
        }

        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new CompoundTag();
            tag.putString("id", id);
            return tag;
        }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("thunderbolt_test", id);
        }
        @Override public void writeToPacket(RegistryFriendlyByteBuf data) { }
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(
                long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean hasComponents() { return false; }
        @Override public boolean equals(Object obj) {
            return obj instanceof TestKey other && id.equals(other.id);
        }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "simulation_key"),
                    TestKey.class, Component.literal("simulation key"));
        }
        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    }
}
