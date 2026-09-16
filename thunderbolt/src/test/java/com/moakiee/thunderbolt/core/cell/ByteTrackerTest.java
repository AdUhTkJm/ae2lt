package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Locks the incremental remainder-delta bookkeeping of {@link ByteTracker} against the plain
 * formula {@code usedBytes = uniqueKeys x bytesPerType + sum(ceil(typeTotal / apb))}, and the
 * per-type entry cleanup when a type's last key is removed.
 */
class ByteTrackerTest {

    private static final int BYTES_PER_TYPE = 8;

    /** Minimal key type with a fixed amount-per-byte; never touches game registries. */
    private static final class TestKeyType extends AEKeyType {
        private final int apb;

        TestKeyType(String id, int apb) {
            super(ResourceLocation.fromNamespaceAndPath("thunderbolt_test", id),
                    AEItemKey.class, Component.literal(id));
            this.apb = apb;
        }

        @Override
        public int getAmountPerByte() {
            return apb;
        }

        @Override
        public MapCodec<? extends AEKey> codec() {
            throw new UnsupportedOperationException("not used by ByteTracker");
        }

        @Override
        public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
            throw new UnsupportedOperationException("not used by ByteTracker");
        }
    }

    /** Naive reference model recomputing used bytes from scratch after every operation. */
    private static final class ReferenceModel {
        final Map<AEKeyType, Long> totals = new HashMap<>();
        final Map<AEKeyType, Integer> keys = new HashMap<>();

        void insert(AEKeyType type, long amount, boolean newKey) {
            totals.merge(type, amount, Long::sum);
            if (newKey) keys.merge(type, 1, Integer::sum);
        }

        void extract(AEKeyType type, long amount, boolean keyRemoved) {
            totals.merge(type, -amount, Long::sum);
            if (totals.get(type) == 0) totals.remove(type);
            if (keyRemoved) {
                keys.merge(type, -1, Integer::sum);
                if (keys.get(type) == 0) keys.remove(type);
            }
        }

        long usedBytes() {
            long used = 0;
            for (var entry : totals.entrySet()) {
                int apb = entry.getKey().getAmountPerByte();
                used += (entry.getValue() + apb - 1) / apb;
            }
            long uniqueKeys = keys.values().stream().mapToInt(Integer::intValue).sum();
            return used + uniqueKeys * BYTES_PER_TYPE;
        }

        int uniqueKeys() {
            return keys.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static ByteTracker configuredTracker(ReferenceModel model) {
        var tracker = new ByteTracker(model::uniqueKeys);
        tracker.configure(BYTES_PER_TYPE, 63, Long.MAX_VALUE, 0);
        return tracker;
    }

    @Test
    void removingTheLastKeyOfATypeDropsItsBookkeepingEntries() {
        var model = new ReferenceModel();
        var tracker = configuredTracker(model);
        var items = new TestKeyType("items", 8);
        var fluids = new TestKeyType("fluids", 8000);

        tracker.onInsert(items, 100, true);
        tracker.onInsert(fluids, 24_000, true);
        assertEquals(4, tracker.trackedTypeEntries());

        tracker.onExtract(fluids, 24_000, true);
        assertEquals(2, tracker.trackedTypeEntries(),
                "removing the last fluid key must drop both per-type entries");

        tracker.onExtract(items, 100, true);
        assertEquals(0, tracker.trackedTypeEntries());
        assertEquals(0, tracker.getUsedBytes());
    }

    @Test
    void interleavedKeysOfOneTypeKeepEntriesUntilTheLastKeyLeaves() {
        var model = new ReferenceModel();
        var tracker = configuredTracker(model);
        var items = new TestKeyType("items", 8);

        tracker.onInsert(items, 3, true);
        tracker.onInsert(items, 5, true);
        tracker.onExtract(items, 3, true);
        assertEquals(2, tracker.trackedTypeEntries(),
                "a remaining key of the type must keep its entries");
        tracker.onExtract(items, 5, true);
        assertEquals(0, tracker.trackedTypeEntries());
        assertEquals(0, tracker.getUsedBytes());
    }

    @Test
    void randomizedSequencesMatchTheClosedFormFormula() {
        var random = new Random(0x7B0);
        var types = new TestKeyType[] {
                new TestKeyType("a", 1),
                new TestKeyType("b", 8),
                new TestKeyType("c", 8000),
        };

        for (int round = 0; round < 200; round++) {
            var model = new ReferenceModel();
            var tracker = configuredTracker(model);
            // Track live per-type key amounts so extracts stay physically consistent.
            List<Map.Entry<AEKeyType, Long>> liveKeys = new ArrayList<>();

            for (int step = 0; step < 120; step++) {
                boolean insertNew = liveKeys.isEmpty() || random.nextInt(4) == 0;
                if (insertNew) {
                    var type = types[random.nextInt(types.length)];
                    long amount = 1 + random.nextLong(20_000);
                    tracker.onInsert(type, amount, true);
                    model.insert(type, amount, true);
                    liveKeys.add(Map.entry(type, amount));
                } else if (random.nextBoolean()) {
                    int pick = random.nextInt(liveKeys.size());
                    var entry = liveKeys.get(pick);
                    long amount = 1 + random.nextLong(20_000);
                    tracker.onInsert(entry.getKey(), amount, false);
                    model.insert(entry.getKey(), amount, false);
                    liveKeys.set(pick, Map.entry(entry.getKey(), entry.getValue() + amount));
                } else {
                    int pick = random.nextInt(liveKeys.size());
                    var entry = liveKeys.get(pick);
                    boolean removeKey = entry.getValue() == 1 || random.nextBoolean();
                    // Partial extracts leave at least one unit so live keys never hit zero.
                    long amount = removeKey
                            ? entry.getValue()
                            : 1 + random.nextLong(entry.getValue() - 1);
                    tracker.onExtract(entry.getKey(), amount, removeKey);
                    model.extract(entry.getKey(), amount, removeKey);
                    if (removeKey) {
                        liveKeys.remove(pick);
                    } else {
                        liveKeys.set(pick, Map.entry(entry.getKey(), entry.getValue() - amount));
                    }
                }
                assertEquals(model.usedBytes(), tracker.getUsedBytes(),
                        "incremental byte accounting diverged from the closed form");
            }
        }
    }
}
