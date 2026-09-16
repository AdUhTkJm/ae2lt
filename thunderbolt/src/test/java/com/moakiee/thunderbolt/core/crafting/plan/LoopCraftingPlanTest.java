package com.moakiee.thunderbolt.core.crafting.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import com.moakiee.thunderbolt.api.crafting.cpu.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.api.crafting.cpu.ExtendedCraftingCpuClusterHost;
import com.moakiee.thunderbolt.core.crafting.loop.CraftingCpuRestrictedPattern;
import com.moakiee.thunderbolt.core.crafting.loop.ReusableSeedPattern;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class LoopCraftingPlanTest {

    @Test
    void wrapsCoreLoopMetadataIntoCorePlanImplementation() {
        var acceptedHost = new TestHost();
        var otherHost = new TestHost();
        var seed = new TestKey("seed");
        var pattern = new TestLoopPattern(seed, acceptedHost);
        var delegate = new CraftingPlan(
                new GenericStack(seed, 1L), 0L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, 1L));

        var wrapped = (LoopCraftingPlan) LoopCraftingPlan.wrapIfNeeded(delegate);

        assertSame(delegate, wrapped.delegate());
        assertEquals(Map.of(seed, 1L), wrapped.totalReusableSeeds());
        assertTrue(wrapped.canRunOn(acceptedHost));
        assertFalse(wrapped.canRunOn(otherHost));
        assertFalse(LoopCraftingPlan.class.getPackageName().contains(".api."));
    }

    private static final class TestHost implements ExtendedCraftingCpuClusterHost {
        @Override
        public ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster() {
            return null;
        }
    }

    private static final class TestLoopPattern
            implements IPatternDetails, CraftingCpuRestrictedPattern, ReusableSeedPattern {
        private final AEKey seed;
        private final ExtendedCraftingCpuClusterHost acceptedHost;
        private final UUID groupId = UUID.randomUUID();

        private TestLoopPattern(AEKey seed, ExtendedCraftingCpuClusterHost acceptedHost) {
            this.seed = seed;
            this.acceptedHost = acceptedHost;
        }

        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(seed, 1L));
        }
        @Override public boolean acceptsCraftingCpu(ExtendedCraftingCpuClusterHost host) {
            return host == acceptedHost;
        }
        @Override public Object reusableSeedStorageScope() { return "host"; }
        @Override public UUID reusableSeedGroupId() { return groupId; }
        @Override public Set<AEKey> reusableSeedCycleKeys() { return Set.of(seed); }
        @Override public boolean hasSingleSeedInputPerMember() { return true; }
        @Override public Map<AEKey, Long> totalReusableSeedRequirements() {
            return Map.of(seed, 1L);
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
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean hasComponents() { return false; }
        @Override public boolean equals(Object obj) {
            return obj instanceof TestKey other && id.equals(other.id);
        }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "loop_key"),
                    TestKey.class, Component.literal("loop key"));
        }

        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    }
}
