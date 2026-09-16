package com.moakiee.thunderbolt.compat.appliede;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.moakiee.thunderbolt.mixin.OptionalMixinSelector;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.Test;

class AppliedEModuleBatchSupportTest {
    static {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void billionCopyBatchUsesOneQueueWriteAndPreservesOtherOutputs() {
        var item = AEItemKey.of(Items.IRON_INGOT);
        var other = AEItemKey.of(Items.GOLD_INGOT);
        var pending = new CountingQueue();
        pending.put(item, 7L);
        pending.put(other, 9L);
        pending.writes = 0;

        assertEquals(0L, AppliedEModuleBatchSupport.enqueue(
                new GenericStack(item, 32L), pending, 1_000_000_000L));
        assertEquals(32_000_000_007L, pending.getLong(item));
        assertEquals(9L, pending.getLong(other));
        assertEquals(1, pending.writes);
    }

    @Test
    void returnsUnacceptedCopiesBeforeOutputAdditionCanOverflow() {
        var item = AEItemKey.of(Items.IRON_INGOT);
        var output = new GenericStack(item, 8L);
        var pending = new CountingQueue();
        pending.put(item, Long.MAX_VALUE - 19L);
        pending.writes = 0;

        assertEquals(2L, AppliedEModuleBatchSupport.capacity(output, pending));
        assertEquals(98L, AppliedEModuleBatchSupport.enqueue(output, pending, 100L));
        assertEquals(Long.MAX_VALUE - 3L, pending.getLong(item));
        assertEquals(1, pending.writes);
        assertEquals(100L, AppliedEModuleBatchSupport.enqueue(output, pending, 100L));
        assertEquals(1, pending.writes);
    }

    @Test
    void largePerCopyOutputsAreMultipliedWithoutWrapping() {
        var item = AEItemKey.of(Items.IRON_INGOT);
        var pending = new CountingQueue();
        assertEquals(Long.MAX_VALUE - 1L, AppliedEModuleBatchSupport.enqueue(
                new GenericStack(item, Long.MAX_VALUE), pending, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, pending.getLong(item));
        assertEquals(1, pending.writes);
    }

    @Test
    void invalidOrEmptyOffersDoNotChangeTheQueue() {
        var item = AEItemKey.of(Items.IRON_INGOT);
        var pending = new CountingQueue();
        assertEquals(12L, AppliedEModuleBatchSupport.enqueue(null, pending, 12L));
        assertEquals(12L, AppliedEModuleBatchSupport.enqueue(
                new GenericStack(item, 0L), pending, 12L));
        assertEquals(0L, AppliedEModuleBatchSupport.enqueue(
                new GenericStack(item, 1L), pending, 0L));
        assertEquals(0, pending.writes);
    }

    @Test
    void addonMixinIsEnabledOnlyWithAppliedE() {
        String mixin = "com.moakiee.thunderbolt.mixin.compat.appliede."
                + "AppliedETransmutationModuleBatchMixin";
        assertFalse(OptionalMixinSelector.shouldApply(mixin, ignored -> false));
        assertFalse(OptionalMixinSelector.shouldApply(mixin, "ae2"::equals));
        assertTrue(OptionalMixinSelector.shouldApply(mixin, "appliede"::equals));
    }

    private static final class CountingQueue extends Object2LongOpenHashMap<AEKey> {
        private int writes;

        @Override
        public long put(AEKey key, long amount) {
            writes++;
            return super.put(key, amount);
        }
    }
}
