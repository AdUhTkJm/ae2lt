package com.moakiee.thunderbolt.core.storage;

import org.jetbrains.annotations.Nullable;

public final class InfiniteCpuStorageFormat {
    private InfiniteCpuStorageFormat() {
    }

    @Nullable
    public static String format(long storage) {
        return storage == Long.MAX_VALUE ? "∞" : null;
    }
}
