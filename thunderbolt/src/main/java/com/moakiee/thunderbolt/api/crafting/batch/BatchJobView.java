package com.moakiee.thunderbolt.api.crafting.batch;

import java.util.Iterator;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.inv.ListCraftingInventory;

/** Live job view borrowed for one batch dispatch. Providers must not retain it. */
public interface BatchJobView {
    Level level();

    Iterator<BatchTaskHandle> taskIterator();

    ListCraftingInventory waitingFor();

    /** Stable ownership id for providers that persist accepted work. */
    @Nullable UUID craftingId();

    default void insertWaitingFor(AEKey what, long amount) {
        waitingFor().insert(what, amount, Actionable.MODULATE);
    }

    void addContainerMaxItems(long count, AEKeyType type);
}
