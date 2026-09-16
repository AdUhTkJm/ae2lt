package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;

import org.junit.jupiter.api.Test;

class PatternInputMatchPolicyTest {
    private static final AEKey NAMED_A = new TestKey("paper", "a");
    private static final AEKey NAMED_B = new TestKey("paper", "b");

    @Test
    void enforcesStrictAndIdOnlyProducerMatrix() {
        var strictInput = new GenericStack[] {new GenericStack(NAMED_A, 1)};

        assertTrue(PatternInputMatchPolicy.accepts(
                strictInput, false, NAMED_A, false),
                "ordinary/overload STRICT output with equal components must feed STRICT input");
        assertFalse(PatternInputMatchPolicy.accepts(
                strictInput, false, NAMED_B, false),
                "different components must not feed STRICT input");
        assertFalse(PatternInputMatchPolicy.accepts(
                strictInput, false, NAMED_A, true),
                "late-bound ID_ONLY output must not feed STRICT input");

        assertTrue(PatternInputMatchPolicy.accepts(
                strictInput, true, NAMED_A, false),
                "ordinary/overload STRICT output must feed ID_ONLY input");
        assertTrue(PatternInputMatchPolicy.accepts(
                strictInput, true, NAMED_B, false),
                "different-component STRICT output must feed ID_ONLY input by item identity");
        assertTrue(PatternInputMatchPolicy.accepts(
                strictInput, true, NAMED_B, true),
                "ID_ONLY output must feed ID_ONLY input by item identity");
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String id;
        private final String variant;

        private TestKey(String id, String variant) {
            this.id = id;
            this.variant = variant;
        }

        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return new TestKey(id, ""); }
        @Override public CompoundTag toTag(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new CompoundTag();
            tag.putString("id", id);
            tag.putString("variant", variant);
            return tag;
        }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("thunderbolt_test", id);
        }
        @Override public void writeToPacket(RegistryFriendlyByteBuf data) { }
        @Override protected Component computeDisplayName() { return Component.literal(id + variant); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean hasComponents() { return !variant.isEmpty(); }
        @Override public boolean equals(Object obj) {
            return obj instanceof TestKey other && id.equals(other.id) && variant.equals(other.variant);
        }
        @Override public int hashCode() { return 31 * id.hashCode() + variant.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "overload_key"),
                    TestKey.class, Component.literal("overload key"));
        }
        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    }
}
