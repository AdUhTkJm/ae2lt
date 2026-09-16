package com.moakiee.thunderbolt.api.eject;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Immutable description of one remote capability projection. */
public record EjectEndpoint(
        ResourceKey<Level> interceptDimension,
        BlockPos interceptPos,
        Direction interceptFace,
        ResourceKey<Level> hostDimension,
        BlockPos hostPos,
        EjectOfflinePolicy offlinePolicy) {

    public EjectEndpoint {
        Objects.requireNonNull(interceptDimension, "interceptDimension");
        interceptPos = Objects.requireNonNull(interceptPos, "interceptPos").immutable();
        Objects.requireNonNull(interceptFace, "interceptFace");
        Objects.requireNonNull(hostDimension, "hostDimension");
        hostPos = Objects.requireNonNull(hostPos, "hostPos").immutable();
        Objects.requireNonNull(offlinePolicy, "offlinePolicy");
    }
}
