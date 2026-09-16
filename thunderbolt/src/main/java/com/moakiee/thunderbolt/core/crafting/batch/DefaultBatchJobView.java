package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ObjLongConsumer;
import java.util.function.ToLongFunction;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.inv.ListCraftingInventory;

import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle;

/** Live mutable view assembled by a crafting-CPU adapter for one batch dispatch. */
public final class DefaultBatchJobView
        implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {
    private final Level level;
    private final @Nullable UUID craftingId;
    private final Map<IPatternDetails, ?> tasks;
    private final ListCraftingInventory waitingFor;
    private final ToLongFunction<Object> valueGetter;
    private final ObjLongConsumer<Object> valueSetter;
    private final Object containerItemTracker;
    private final ContainerItemAccounting containerItemAccounting;
    private Iterator<? extends Map.Entry<IPatternDetails, ?>> rawIterator;
    private Map.Entry<IPatternDetails, ?> currentEntry;

    public DefaultBatchJobView(
            Level level,
            @Nullable UUID craftingId,
            Map<IPatternDetails, ?> tasks,
            ListCraftingInventory waitingFor,
            ToLongFunction<Object> valueGetter,
            ObjLongConsumer<Object> valueSetter,
            Object containerItemTracker,
            ContainerItemAccounting containerItemAccounting) {
        this.level = Objects.requireNonNull(level, "level");
        this.craftingId = craftingId;
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.waitingFor = Objects.requireNonNull(waitingFor, "waitingFor");
        this.valueGetter = Objects.requireNonNull(valueGetter, "valueGetter");
        this.valueSetter = Objects.requireNonNull(valueSetter, "valueSetter");
        this.containerItemTracker = Objects.requireNonNull(
                containerItemTracker, "containerItemTracker");
        this.containerItemAccounting = Objects.requireNonNull(
                containerItemAccounting, "containerItemAccounting");
    }

    @Override
    public Level level() {
        return level;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterator<BatchTaskHandle> taskIterator() {
        rawIterator = (Iterator<? extends Map.Entry<IPatternDetails, ?>>)
                tasks.entrySet().iterator();
        currentEntry = null;
        return this;
    }

    @Override
    public boolean hasNext() {
        return rawIterator.hasNext();
    }

    @Override
    public BatchTaskHandle next() {
        currentEntry = rawIterator.next();
        return this;
    }

    @Override
    public void remove() {
        rawIterator.remove();
        currentEntry = null;
    }

    @Override
    public IPatternDetails details() {
        return requireCurrentEntry().getKey();
    }

    @Override
    public long getValue() {
        return valueGetter.applyAsLong(requireCurrentEntry().getValue());
    }

    @Override
    public void setValue(long value) {
        valueSetter.accept(requireCurrentEntry().getValue(), value);
    }

    @Override
    public ListCraftingInventory waitingFor() {
        return waitingFor;
    }

    @Override
    public @Nullable UUID craftingId() {
        return craftingId;
    }

    @Override
    public void addContainerMaxItems(long count, AEKeyType type) {
        containerItemAccounting.add(containerItemTracker, count, type);
    }

    private Map.Entry<IPatternDetails, ?> requireCurrentEntry() {
        if (currentEntry == null) {
            throw new IllegalStateException("No current crafting task");
        }
        return currentEntry;
    }

    @FunctionalInterface
    public interface ContainerItemAccounting {
        void add(Object tracker, long count, AEKeyType type);
    }
}
