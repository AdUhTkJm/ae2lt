package com.moakiee.thunderbolt.core.storage.cell;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import com.moakiee.thunderbolt.core.storage.cell.ByteTracker;

/**
 * Item-side definition consumed by Thunderbolt's shared indexed-cell handler.
 * Implementations own presentation, filtering and accounting policy; Thunderbolt owns storage.
 */
public interface IIndexedStorageCellItem {
    /** Stable namespace inside Thunderbolt's world SavedData. Never derive this from display data. */
    ResourceLocation storageType(ItemStack stack);

    /** Stable UUID tag retained on the ItemStack. Existing mods should keep their legacy tag here. */
    default String cellIdTag(ItemStack stack) {
        return "thunderbolt:indexed_cell_id";
    }

    /** Returns a configured tracker whose total-type supplier points at the provided storage. */
    ByteTracker createByteTracker(ItemStack stack, IndexedStorage storage);

    double idleDrain(ItemStack stack);

    default boolean accepts(ItemStack stack, AEKey key, IActionSource source) {
        return true;
    }

    default boolean isPreferred(
            ItemStack stack, AEKey key, IndexedStorage storage, IActionSource source) {
        return storage.containsKey(key);
    }

    default void writeSummary(ItemStack stack, IndexedCellSummary summary) {}
}
