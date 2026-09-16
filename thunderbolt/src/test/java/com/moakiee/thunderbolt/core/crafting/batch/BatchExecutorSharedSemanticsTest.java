package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;

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
import appeng.api.stacks.KeyCounter;

class BatchExecutorSharedSemanticsTest {

    @Test
    void executionPatternMustRecognizeTheExtractedSharedInput() {
        var seed = key();
        var task = pattern(seed, true, 1L);
        var execution = pattern(seed, false, 1L);
        var result = sharedResult(seed);

        assertFalse(BatchExecutor.sharedBatchSemanticsMatch(task, execution, result));
    }

    @Test
    void taskAndExecutionMustAgreeOnTheSharedOutputAmount() {
        var seed = key();
        var task = pattern(seed, true, 1L);
        var execution = pattern(seed, true, 0L);
        var result = sharedResult(seed);

        assertFalse(BatchExecutor.sharedBatchSemanticsMatch(task, execution, result));
    }

    @Test
    void matchingPhysicalSemanticsAllowTheSharedBatch() {
        var seed = key();
        var task = pattern(seed, true, 1L);
        var execution = pattern(seed, true, 1L);
        var result = sharedResult(seed);

        assertTrue(BatchExecutor.sharedBatchSemanticsMatch(task, execution, result));
    }

    private static AEKey key() {
        return new TestKey("smithing_template");
    }

    private static ParallelBatchCpuHelper.BulkResult sharedResult(AEKey seed) {
        var scaled = new KeyCounter[]{new KeyCounter()};
        scaled[0].add(seed, 1L);
        return new ParallelBatchCpuHelper.BulkResult(
                scaled, 5L, new AEKey[]{seed}, new long[]{1L}, new boolean[]{true});
    }

    private static IPatternDetails pattern(
            AEKey seed, boolean recognizesSharedInput, long sharedOutput) {
        var outputs = List.of(new GenericStack(seed, 2L));
        var input = (IPatternDetails.IInput) Proxy.newProxyInstance(
                BatchExecutorSharedSemanticsTest.class.getClassLoader(),
                new Class<?>[]{IPatternDetails.IInput.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        var interfaces = recognizesSharedInput
                ? new Class<?>[]{IPatternDetails.class, SharedBatchInputPattern.class}
                : new Class<?>[]{IPatternDetails.class};
        return (IPatternDetails) Proxy.newProxyInstance(
                BatchExecutorSharedSemanticsTest.class.getClassLoader(),
                interfaces,
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInputs" -> new IPatternDetails.IInput[]{input};
                    case "getOutputs" -> method.getReturnType().isArray()
                            ? outputs.toArray(GenericStack[]::new)
                            : outputs;
                    case "isSharedBatchInput" -> recognizesSharedInput
                            && ((int) args[0]) == 0 && seed.equals(args[1]);
                    case "sharedBatchOutputAmount" -> seed.equals(args[0])
                            ? sharedOutput : 0L;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "batch-semantics-pattern";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
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
            super(ResourceLocation.fromNamespaceAndPath(
                    "thunderbolt_test", "batch_semantics_key"),
                    TestKey.class, Component.literal("batch semantics key"));
        }
        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    }
}
