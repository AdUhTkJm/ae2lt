package com.moakiee.thunderbolt.core.storage.cell;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import com.moakiee.thunderbolt.core.storage.cell.IndexedCellStorageSavedData;

/** Public lifecycle/cache facade for world-backed {@link IndexedStorage} cell contents. */
public final class IndexedCellStorageRegistry {
    private final IndexedCellStorageSavedData data;

    private IndexedCellStorageRegistry(IndexedCellStorageSavedData data) {
        this.data = data;
    }

    public static IndexedCellStorageRegistry get(MinecraftServer server) {
        return new IndexedCellStorageRegistry(IndexedCellStorageSavedData.get(server));
    }

    public static @Nullable IndexedCellStorageRegistry getOrNull() {
        var server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? get(server) : null;
    }

    public IndexedStorage getOrCreateStorage(
            ResourceLocation type, UUID id, HolderLookup.Provider registries) {
        return data.getOrCreateStorage(type, id, registries);
    }

    public void persistStorage(
            ResourceLocation type,
            UUID id,
            IndexedStorage storage,
            HolderLookup.Provider registries) {
        data.persistStorage(type, id, storage, registries);
    }

    public void markStorageDirty(ResourceLocation type, UUID id, IndexedStorage storage) {
        data.markStorageDirty(type, id, storage);
    }

    public void removeCell(ResourceLocation type, UUID id) {
        data.removeCell(type, id);
    }
}
