package com.moakiee.thunderbolt.core.storage.cell;

import org.jetbrains.annotations.Nullable;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.world.item.ItemStack;

/** Single AE2 cell handler shared by all items implementing {@link IIndexedStorageCellItem}. */
public final class IndexedStorageCellHandler implements ICellHandler {
    public static final IndexedStorageCellHandler INSTANCE = new IndexedStorageCellHandler();

    private IndexedStorageCellHandler() {}

    @Override
    public boolean isCell(ItemStack stack) {
        return stack.getItem() instanceof IIndexedStorageCellItem;
    }

    @Override
    public @Nullable StorageCell getCellInventory(ItemStack stack, @Nullable ISaveProvider host) {
        return stack.getItem() instanceof IIndexedStorageCellItem definition
                ? new IndexedStorageCellInventory(stack, definition, null, host)
                : null;
    }
}
