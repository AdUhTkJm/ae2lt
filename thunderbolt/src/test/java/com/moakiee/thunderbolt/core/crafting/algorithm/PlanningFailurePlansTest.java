package com.moakiee.thunderbolt.core.crafting.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

class PlanningFailurePlansTest {
    @Test
    void allFailedPlanIsAnEmptyFailClosedSimulation() {
        var output = new TestKey();

        var plan = PlanningFailurePlans.allFailed(output, 37);

        assertEquals(output, plan.finalOutput().what());
        assertEquals(37, plan.finalOutput().amount());
        assertEquals(0, plan.bytes());
        assertTrue(plan.simulation());
        assertFalse(plan.multiplePaths());
        assertTrue(plan.usedItems().isEmpty());
        assertTrue(plan.missingItems().isEmpty());
        assertTrue(plan.emittedItems().isEmpty());
        assertTrue(plan.patternTimes().isEmpty());
    }

    private static final class TestKey extends AEKey {
        @Override public AEKeyType getType() { return null; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag(HolderLookup.Provider registries) { return new CompoundTag(); }
        @Override public Object getPrimaryKey() { return this; }
        @Override public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "output");
        }
        @Override public void writeToPacket(RegistryFriendlyByteBuf buffer) { }
        @Override protected Component computeDisplayName() { return Component.literal("test"); }
        @Override public void addDrops(
                long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean hasComponents() { return false; }
    }
}
