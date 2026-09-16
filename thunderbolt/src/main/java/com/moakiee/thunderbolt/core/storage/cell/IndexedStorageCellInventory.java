package com.moakiee.thunderbolt.core.storage.cell;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import com.moakiee.thunderbolt.core.storage.cell.ByteTracker;

/** Shared AE2 {@link StorageCell} wrapper for every {@link IIndexedStorageCellItem}. */
public final class IndexedStorageCellInventory implements StorageCell {
    private final ItemStack stack;
    private final IIndexedStorageCellItem definition;
    private final @Nullable HolderLookup.Provider explicitRegistries;
    private final @Nullable ISaveProvider saveProvider;
    private final ResourceLocation storageType;
    private final String cellIdTag;
    private final IndexedStorage storage;
    private final ByteTracker byteTracker;

    private @Nullable UUID cellId;
    private long lastSyncModCount = -1;
    private int lastWrittenTypes = -1;
    private long lastWrittenBytes = -1;

    public IndexedStorageCellInventory(
            ItemStack stack,
            IIndexedStorageCellItem definition,
            @Nullable HolderLookup.Provider registries,
            @Nullable ISaveProvider saveProvider) {
        this.stack = stack;
        this.definition = definition;
        this.explicitRegistries = registries;
        this.saveProvider = saveProvider;
        this.storageType = definition.storageType(stack);
        this.cellIdTag = definition.cellIdTag(stack);
        this.cellId = readCellId();

        var savedData = IndexedCellStorageRegistry.getOrNull();
        this.storage = cellId != null && savedData != null
                ? savedData.getOrCreateStorage(storageType, cellId, resolveRegistries())
                : new IndexedStorage();
        this.byteTracker = definition.createByteTracker(stack, storage);
        syncByteTracker();
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !definition.accepts(stack, key, source)) return 0;
        ensureSync();
        boolean newKey = !storage.containsKey(key);
        long accepted = Math.min(amount, byteTracker.computeMaxInsertable(key.getType(), newKey));
        if (accepted <= 0 || mode == Actionable.SIMULATE) return Math.max(accepted, 0);

        storage.insert(key, accepted, Actionable.MODULATE);
        byteTracker.onInsert(key.getType(), accepted, newKey);
        lastSyncModCount = storage.getModCount();
        syncSummary();
        markChanged();
        return accepted;
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0) return 0;
        ensureSync();
        if (mode == Actionable.SIMULATE) {
            return storage.extract(key, amount, Actionable.SIMULATE);
        }
        long extracted = storage.extract(key, amount, Actionable.MODULATE);
        if (extracted > 0) {
            boolean keyRemoved = !storage.containsKey(key);
            byteTracker.onExtract(key.getType(), extracted, keyRemoved);
            lastSyncModCount = storage.getModCount();
            syncSummary();
            markChanged();
        }
        return extracted;
    }

    @Override public void getAvailableStacks(KeyCounter out) { storage.getAvailableStacks(out); }

    @Override
    public boolean isPreferredStorageFor(AEKey key, IActionSource source) {
        return definition.isPreferred(stack, key, storage, source);
    }

    @Override public Component getDescription() { return stack.getHoverName(); }

    @Override
    public CellState getStatus() {
        ensureSync();
        if (storage.getTotalTypes() == 0) return CellState.EMPTY;
        if (byteTracker.isFull()) return CellState.FULL;
        if (byteTracker.isTypeFull()) return CellState.TYPES_FULL;
        return CellState.NOT_EMPTY;
    }

    @Override public double getIdleDrain() { return definition.idleDrain(stack); }

    @Override
    public boolean canFitInsideCell() {
        ensureSync();
        return storage.getTotalTypes() == 0;
    }

    @Override
    public void persist() {
        var savedData = IndexedCellStorageRegistry.getOrNull();
        if (savedData == null) return;
        if (storage.getTotalTypes() == 0) {
            if (storage.needsPersist()) storage.persist(null, resolveRegistries());
            if (cellId != null) {
                savedData.removeCell(storageType, cellId);
                clearCellId();
                cellId = null;
            }
            syncSummary();
            return;
        }
        if (!storage.needsPersist()) return;
        ensureCellId();
        savedData.persistStorage(storageType, cellId, storage, resolveRegistries());
        ensureSync();
        syncSummary();
    }

    public IndexedStorage storage() { return storage; }

    public long usedBytes() {
        ensureSync();
        return byteTracker.getUsedBytes();
    }

    private void markChanged() {
        if (storage.getTotalTypes() == 0) {
            persist();
        } else {
            var savedData = IndexedCellStorageRegistry.getOrNull();
            if (savedData != null) {
                ensureCellId();
                savedData.markStorageDirty(storageType, cellId, storage);
            }
        }
        if (saveProvider != null) saveProvider.saveChanges();
    }

    private void ensureSync() {
        if (storage.getModCount() != lastSyncModCount) syncByteTracker();
    }

    private void syncByteTracker() {
        byteTracker.rebuild(
                storage.getTypeAmountLo(),
                storage.getTypeAmountHi(),
                storage.getTypeCounts(),
                storage.getTotalTypes());
        lastSyncModCount = storage.getModCount();
    }

    private void syncSummary() {
        int types = storage.getTotalTypes();
        long bytes = byteTracker.getUsedBytes();
        if (types == lastWrittenTypes && bytes == lastWrittenBytes) return;
        lastWrittenTypes = types;
        lastWrittenBytes = bytes;
        definition.writeSummary(stack, new IndexedCellSummary(types, bytes));
    }

    private @Nullable UUID readCellId() {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.hasUUID(cellIdTag) ? tag.getUUID(cellIdTag) : null;
    }

    private void ensureCellId() {
        if (cellId != null) return;
        cellId = UUID.randomUUID();
        UUID id = cellId;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putUUID(cellIdTag, id));
    }

    private void clearCellId() {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(cellIdTag));
    }

    private HolderLookup.Provider resolveRegistries() {
        if (explicitRegistries != null) return explicitRegistries;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) return server.registryAccess();
        throw new IllegalStateException("No registries available for indexed cell persistence");
    }
}
