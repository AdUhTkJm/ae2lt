package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.mojang.serialization.MapCodec;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;

class ThunderboltCraftingPlanSummaryTest {
    private static final AEKey MATERIAL = new TestKey("material");
    private static final AEKey EMITTED = new TestKey("emitted");
    private static final AEKey PRODUCT = new TestKey("product");

    @Test
    void routesEveryProxyEngineButLeavesAe2VanillaOnItsNativeSummary() {
        assertFalse(ThunderboltCraftingPlanSummary.handles(null));
        assertFalse(ThunderboltCraftingPlanSummary.handles(
                CraftingPlanningEngines.VANILLA_ID));
        assertTrue(ThunderboltCraftingPlanSummary.handles(
                ResourceLocation.fromNamespaceAndPath("another_mod", "registered_engine")));
        assertTrue(ThunderboltCraftingPlanSummary.handles(
                CraftingPlanningEngines.ALL_FAILED_ID));
    }

    @Test
    void simulationSummaryUsesThePlanningSnapshotSplit() {
        var used = new KeyCounter();
        used.add(MATERIAL, 83_751_862_233L);
        var missing = new KeyCounter();
        missing.add(MATERIAL, 303_668_626_767L);
        var plan = new CraftingPlan(
                null,
                123L,
                true,
                false,
                used,
                new KeyCounter(),
                missing,
                Map.of());

        var summary = ThunderboltCraftingPlanSummary.fromPlan(plan);

        assertEquals(123L, summary.getUsedBytes());
        assertTrue(summary.isSimulation());
        assertEquals(1, summary.getEntries().size());
        var entry = summary.getEntries().getFirst();
        assertEquals(MATERIAL, entry.getWhat());
        assertEquals(83_751_862_233L, entry.getStoredAmount());
        assertEquals(303_668_626_767L, entry.getMissingAmount());
        assertEquals(0L, entry.getCraftAmount());
    }

    @Test
    void successfulProxyPlanDoesNotInventMissingItems() {
        var used = new KeyCounter();
        used.add(MATERIAL, 10L);
        var plan = new CraftingPlan(
                null,
                1L,
                false,
                false,
                used,
                new KeyCounter(),
                new KeyCounter(),
                Map.of());

        var entry = ThunderboltCraftingPlanSummary.fromPlan(plan).getEntries().getFirst();

        assertEquals(10L, entry.getStoredAmount());
        assertEquals(0L, entry.getMissingAmount());
    }

    @Test
    void preservesEmitterAndPatternCraftingAmounts() {
        var used = new KeyCounter();
        used.add(MATERIAL, 4L);
        var emitted = new KeyCounter();
        emitted.add(EMITTED, 2L);
        var pattern = new FakePattern(PRODUCT, 3L);
        var plan = new CraftingPlan(
                null,
                1L,
                false,
                false,
                used,
                emitted,
                new KeyCounter(),
                Map.of(pattern, 5L));

        var summary = ThunderboltCraftingPlanSummary.fromPlan(plan);

        assertEquals(4L, entry(summary, MATERIAL).getStoredAmount());
        assertEquals(2L, entry(summary, EMITTED).getStoredAmount());
        assertEquals(2L, entry(summary, EMITTED).getCraftAmount());
        assertEquals(15L, entry(summary, PRODUCT).getCraftAmount());
    }

    private static CraftingPlanSummaryEntry entry(CraftingPlanSummary summary, AEKey key) {
        return summary.getEntries().stream()
                .filter(entry -> entry.getWhat().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private record FakePattern(AEKey output, long amount) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(output, amount));
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
            super(ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "summary_key"),
                    TestKey.class, Component.literal("summary key"));
        }

        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    }
}
