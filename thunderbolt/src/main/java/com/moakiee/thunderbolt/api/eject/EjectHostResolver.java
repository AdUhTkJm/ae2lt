package com.moakiee.thunderbolt.api.eject;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Resolves the live host for an endpoint without exposing runtime index implementation details. */
@FunctionalInterface
public interface EjectHostResolver {

    @Nullable
    BlockEntity resolve(MinecraftServer server, EjectEndpoint endpoint);
}
