package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;

import org.junit.jupiter.api.Test;

class FastCraftingPlannerOverloadMatchingTest {
    private static final AEKey TEMPLATE = new TestKey("paper", "");
    private static final AEKey CRAFTABLE_VARIANT = new TestKey("paper", "named-b");
    private static final AEKey OTHER_ITEM = new TestKey("iron", "damaged");

    @Test
    void nativeInputExpansionIncludesValidCraftableVariantsThatAreNotInStock() {
        IPatternDetails.IInput input = new IPatternDetails.IInput() {
            @Override public GenericStack[] getPossibleInputs() {
                return new GenericStack[] {new GenericStack(TEMPLATE, 3)};
            }
            @Override public long getMultiplier() { return 1; }
            @Override public boolean isValid(AEKey key, Level level) {
                return TEMPLATE.dropSecondary().equals(key.dropSecondary());
            }
            @Override public AEKey getRemainingKey(AEKey template) { return null; }
        };

        var expanded = FastCraftingPlanner.expandInputTemplates(
                input,
                ignored -> List.of(),
                ignored -> List.of(CRAFTABLE_VARIANT),
                null);

        assertEquals(2, expanded.size());
        assertTrue(expanded.stream().anyMatch(stack -> stack.what().equals(CRAFTABLE_VARIANT)));
        assertEquals(3L, expanded.stream()
                .filter(stack -> stack.what().equals(CRAFTABLE_VARIANT))
                .findFirst().orElseThrow().amount());
    }

    @Test
    void nativeInputExpansionFiltersEveryDiscoveredCandidateThroughIsValid() {
        IPatternDetails.IInput input = new IPatternDetails.IInput() {
            @Override public GenericStack[] getPossibleInputs() {
                return new GenericStack[] {new GenericStack(TEMPLATE, 2)};
            }
            @Override public long getMultiplier() { return 1; }
            @Override public boolean isValid(AEKey key, Level level) {
                return TEMPLATE.equals(key) || CRAFTABLE_VARIANT.equals(key);
            }
            @Override public AEKey getRemainingKey(AEKey template) { return null; }
        };

        var rejectedStock = new TestKey("paper", "rejected-stock");
        var rejectedCraftable = new TestKey("paper", "rejected-craftable");
        var expanded = FastCraftingPlanner.expandInputTemplates(
                input,
                ignored -> List.of(CRAFTABLE_VARIANT, rejectedStock),
                ignored -> List.of(CRAFTABLE_VARIANT, rejectedCraftable),
                null);

        assertEquals(List.of(TEMPLATE, CRAFTABLE_VARIANT),
                expanded.stream().map(GenericStack::what).toList());
    }

    @Test
    void lateStrictDemandForcesMixedModeBeforeGraphRebuild() {
        var root = new TestKey("root", "");
        var modes = new FastCraftingPlanner.RequirementModes(root, Map.of());

        modes.require(TEMPLATE, FastCraftingPlanner.RequirementMode.ID_ONLY);
        modes.markProcessed(TEMPLATE);
        modes.require(TEMPLATE, FastCraftingPlanner.RequirementMode.STRICT);

        assertEquals(FastCraftingPlanner.RequirementMode.MIXED, modes.modeFor(TEMPLATE));
        assertEquals(
                FastCraftingPlanner.RequirementMode.MIXED,
                modes.lateChanges().get(TEMPLATE));

        var rebuilt = new FastCraftingPlanner.RequirementModes(root, modes.lateChanges());
        assertEquals(FastCraftingPlanner.RequirementMode.MIXED, rebuilt.modeFor(TEMPLATE));
    }

    @Test
    void craftableIdentityIndexScansNetworkOnlyOnce() {
        var scans = new AtomicInteger();
        var index = new FastCraftingPlanner.PrimaryIdentityCraftables(() -> {
            scans.incrementAndGet();
            return List.of(TEMPLATE, CRAFTABLE_VARIANT, OTHER_ITEM);
        });

        assertEquals(List.of(TEMPLATE, CRAFTABLE_VARIANT), index.get(TEMPLATE.dropSecondary()));
        assertEquals(List.of(OTHER_ITEM), index.get(OTHER_ITEM.dropSecondary()));
        assertEquals(List.of(TEMPLATE, CRAFTABLE_VARIANT), index.get(TEMPLATE.dropSecondary()));
        assertEquals(1, scans.get());
    }

    @Test
    void nonCraftingPatternDoesNotEnableDurabilityInference() {
        IPatternDetails processingLikePattern = new IPatternDetails() {
            @Override public appeng.api.stacks.AEItemKey getDefinition() { return null; }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() { return List.of(); }
        };

        assertFalse(FastCraftingPlanner.isCraftingPattern(processingLikePattern));
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
            return new CompoundTag();
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
            super(ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "planner_overload_key"),
                    TestKey.class, Component.literal("planner overload key"));
        }
        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    }
}
