package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.pattern.AECraftingPattern;
import net.neoforged.fml.loading.LoadingModList;

import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

class FastCraftingPlannerDurabilityEligibilityTest {
    static {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final AEKey FULL_TOOL = new TestKey("tool", "full");
    private static final AEKey DAMAGED_TOOL = new TestKey("tool", "damaged");

    private static final AEItemKey A = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey B = AEItemKey.of(Items.WOODEN_PICKAXE);
    private static final AEItemKey C = AEItemKey.of(Items.STICK);
    private static final AEItemKey D = AEItemKey.of(Items.DIAMOND);

    @Test
    void strictPatternDoesNotTreatRejectedDamagedToolAsReusable() {
        var input = new TestInput(false);

        assertFalse(FastCraftingPlanner.acceptsDurabilityRemainder(
                input, DAMAGED_TOOL, null));
    }

    @Test
    void substitutionPatternKeepsAcceptedDamagedToolInDurabilityChain() {
        var input = new TestInput(true);

        assertTrue(FastCraftingPlanner.acceptsDurabilityRemainder(
                input, DAMAGED_TOOL, null));
    }

    /** A -> B; B* + C -> B*-1 + D. Fuzzy B* reuses one crafted tool for both D. */
    @Test
    void aToBThenDurabilityRecipeReusesBWhenFuzzyIsEnabled() {
        IPatternDetails makeB = new FakePattern(B, new IPatternDetails.IInput[] {
                new ExactInput(A)
        });
        IPatternDetails makeD = TestCraftingPattern.create(D, new IPatternDetails.IInput[] {
                new DurabilityInput(B, true),
                new ExactInput(C)
        });
        var service = new FakeCraftingService()
                .pattern(B, makeB)
                .pattern(D, makeD)
                .craftable(B)
                .craftable(D);
        var inventory = new ChildCraftingSimulationState(
                new StockInventory(Map.of(A, 1L, C, 2L)));

        var attempt = FastCraftingPlanner.tryAttempt(service, inventory, null, D, 2, false);

        assertTrue(attempt.handled());
        assertNotNull(attempt.plan(),
                "fuzzy B* must reuse B*-1 instead of crafting a second B");
        assertTrue(attempt.plan().missingItems().isEmpty());
        assertEquals(1L, attempt.plan().patternTimes().get(makeB));
        assertEquals(2L, attempt.plan().patternTimes().get(makeD));
        assertEquals(1L, attempt.plan().usedItems().get(A));
        assertEquals(2L, attempt.plan().usedItems().get(C));
    }

    /** A -> B; B* + C -> B*-1 + D. Strict B cannot consume B*-1 on the second craft. */
    @Test
    void aToBThenDurabilityRecipeReportsSecondBMissingWhenFuzzyIsDisabled() {
        IPatternDetails makeB = new FakePattern(B, new IPatternDetails.IInput[] {
                new ExactInput(A)
        });
        IPatternDetails makeD = TestCraftingPattern.create(D, new IPatternDetails.IInput[] {
                new DurabilityInput(B, false),
                new ExactInput(C)
        });
        var service = new FakeCraftingService()
                .pattern(B, makeB)
                .pattern(D, makeD)
                .craftable(B)
                .craftable(D);
        var inventory = new ChildCraftingSimulationState(
                new StockInventory(Map.of(A, 1L, C, 2L)));

        var attempt = FastCraftingPlanner.tryAttempt(service, inventory, null, D, 2, false);

        assertTrue(attempt.handled());
        assertNull(attempt.plan(),
                "strict B must not produce a feasible plan by reusing rejected B*-1");
        assertNotNull(attempt.simulationFallback());
        assertEquals(1L, attempt.simulationFallback().missingItems().get(A),
                "two strict crafts need two fresh B, hence two A");
        assertEquals(2L, attempt.simulationFallback().patternTimes().get(makeB));
        assertEquals(2L, attempt.simulationFallback().patternTimes().get(makeD));
    }

    private record TestInput(boolean allowVariants) implements IPatternDetails.IInput {
        @Override public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(FULL_TOOL, 1)};
        }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey key, Level level) {
            return allowVariants
                    ? FULL_TOOL.dropSecondary().equals(key.dropSecondary())
                    : FULL_TOOL.equals(key);
        }
        @Override public AEKey getRemainingKey(AEKey key) {
            return FULL_TOOL.equals(key) ? DAMAGED_TOOL : null;
        }
    }

    private record ExactInput(AEKey key) implements IPatternDetails.IInput {
        @Override public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(key, 1)};
        }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey candidate, Level level) {
            return key.equals(candidate);
        }
        @Override public AEKey getRemainingKey(AEKey candidate) { return null; }
    }

    private record DurabilityInput(AEItemKey encoded, boolean fuzzy)
            implements IPatternDetails.IInput {
        @Override public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(encoded, 1)};
        }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey candidate, Level level) {
            return candidate instanceof AEItemKey item
                    && (fuzzy ? item.getItem() == encoded.getItem() : encoded.equals(item));
        }
        @Override public AEKey getRemainingKey(AEKey candidate) {
            if (!(candidate instanceof AEItemKey item) || item.getItem() != encoded.getItem()) {
                return null;
            }
            ItemStack remainder = item.toStack();
            int nextDamage = remainder.getDamageValue() + 1;
            if (nextDamage >= remainder.getMaxDamage()) {
                return null;
            }
            remainder.setDamageValue(nextDamage);
            return AEItemKey.of(remainder);
        }
    }

    private record FakePattern(AEKey output, IInput[] inputs) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(output, 1));
        }
    }

    /**
     * The refactored adapter deliberately enables durability inference only for an actual
     * {@link AECraftingPattern}. AE2 needs a live level and recipe manager to construct one normally,
     * so this test-only subclass is allocated without running that external constructor and overrides
     * the complete {@link IPatternDetails} surface used by the planner.
     */
    private static final class TestCraftingPattern extends AECraftingPattern {
        private IInput[] testInputs;
        private List<GenericStack> testOutputs;

        private TestCraftingPattern() {
            super(null, null);
        }

        static TestCraftingPattern create(AEKey output, IInput[] inputs) {
            try {
                var pattern = (TestCraftingPattern) unsafe().allocateInstance(
                        TestCraftingPattern.class);
                pattern.testInputs = inputs;
                pattern.testOutputs = List.of(new GenericStack(output, 1));
                return pattern;
            } catch (InstantiationException e) {
                throw new AssertionError(e);
            }
        }

        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return testInputs; }
        @Override public List<GenericStack> getOutputs() { return testOutputs; }
        @Override public boolean equals(Object obj) { return this == obj; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class FakeCraftingService implements ICraftingService {
        private final Map<AEKey, List<IPatternDetails>> patterns = new LinkedHashMap<>();
        private final Set<AEKey> craftables = new java.util.LinkedHashSet<>();

        FakeCraftingService pattern(AEKey output, IPatternDetails details) {
            patterns.computeIfAbsent(output, ignored -> new ArrayList<>()).add(details);
            return this;
        }

        FakeCraftingService craftable(AEKey key) {
            craftables.add(key);
            return this;
        }

        @Override public java.util.Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
            return patterns.getOrDefault(whatToCraft, List.of());
        }
        @Override public void refreshNodeCraftingProvider(IGridNode node) { }
        @Override public void addGlobalCraftingProvider(ICraftingProvider provider) { }
        @Override public void removeGlobalCraftingProvider(ICraftingProvider provider) { }
        @Override public void refreshGlobalCraftingProvider(ICraftingProvider provider) { }
        @Override public AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter) {
            for (AEKey key : craftables) {
                if (key.getId().equals(whatToCraft.getId()) && filter.matches(key)) {
                    return key;
                }
            }
            return null;
        }
        @Override public Future<ICraftingPlan> beginCraftingCalculation(
                Level level, ICraftingSimulationRequester simRequester, AEKey what, long amount,
                CalculationStrategy strategy) {
            throw new UnsupportedOperationException();
        }
        @Override public ICraftingSubmitResult submitJob(
                ICraftingPlan job,
                appeng.api.networking.crafting.ICraftingRequester requestingMachine,
                ICraftingCPU target,
                boolean prioritizePower,
                IActionSource src) {
            throw new UnsupportedOperationException();
        }
        @Override public ImmutableSet<ICraftingCPU> getCpus() { return ImmutableSet.of(); }
        @Override public boolean canEmitFor(AEKey what) { return false; }
        @Override public Set<AEKey> getCraftables(AEKeyFilter filter) {
            var result = new java.util.LinkedHashSet<AEKey>();
            for (AEKey key : craftables) {
                if (filter.matches(key)) {
                    result.add(key);
                }
            }
            return result;
        }
        @Override public boolean isRequesting(AEKey what) { return false; }
        @Override public long getRequestedAmount(AEKey what) { return 0; }
        @Override public boolean isRequestingAny() { return false; }
    }

    private static final class StockInventory implements ICraftingInventory {
        private final Map<AEKey, Long> stock;

        StockInventory(Map<AEKey, Long> stock) {
            this.stock = new HashMap<>(stock);
        }

        @Override public void insert(AEKey key, long amount, Actionable mode) {
            if (mode == Actionable.MODULATE) {
                stock.merge(key, amount, Long::sum);
            }
        }

        @Override public long extract(AEKey key, long amount, Actionable mode) {
            long available = stock.getOrDefault(key, 0L);
            long taken = Math.min(available, amount);
            if (mode == Actionable.MODULATE && taken > 0) {
                stock.put(key, available - taken);
            }
            return taken;
        }

        @Override public Iterable<AEKey> findFuzzyTemplates(AEKey key) {
            var matches = new ArrayList<AEKey>();
            for (AEKey candidate : stock.keySet()) {
                if (candidate.getType() == key.getType()
                        && candidate.getId().equals(key.getId())) {
                    matches.add(candidate);
                }
            }
            return matches;
        }
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
        @Override protected Component computeDisplayName() {
            return Component.literal(id + "#" + variant);
        }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean hasComponents() { return !variant.isEmpty(); }
        @Override public boolean equals(Object obj) {
            return obj instanceof TestKey other
                    && id.equals(other.id)
                    && variant.equals(other.variant);
        }
        @Override public int hashCode() { return 31 * id.hashCode() + variant.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(ResourceLocation.fromNamespaceAndPath(
                            "thunderbolt_test", "durability_eligibility_key"),
                    TestKey.class, Component.literal("durability eligibility key"));
        }
        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    }
}
