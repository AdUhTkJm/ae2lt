package com.moakiee.thunderbolt.api.eject;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Stable registration facade for Eject capability projections. */
public final class EjectCapabilityRegistry {

    private static volatile Runtime runtime = Runtime.unavailable();

    private EjectCapabilityRegistry() {
    }

    public static EjectRegistration register(EjectEndpoint endpoint) {
        return register(endpoint, EjectCapabilityRegistry::resolveStableHost);
    }

    public static EjectRegistration register(EjectEndpoint endpoint, EjectHostResolver resolver) {
        return runtime.register(Objects.requireNonNull(endpoint, "endpoint"),
                Objects.requireNonNull(resolver, "resolver"));
    }

    public static void unregister(ResourceKey<Level> dimension, BlockPos pos, Direction face) {
        runtime.unregister(dimension, pos, face);
    }

    public static List<EndpointLocation> unregisterAll(BlockEntity host) {
        return runtime.unregisterAll(host);
    }

    public static void setBypass(boolean bypass) {
        runtime.setBypass(bypass);
    }

    public static boolean isBypassed() {
        return runtime.isBypassed();
    }

    /** Installed once by Thunderbolt Core; not an extension point for content mods. */
    public static void installRuntime(Runtime implementation) {
        runtime = Objects.requireNonNull(implementation, "implementation");
    }

    private static BlockEntity resolveStableHost(
            net.minecraft.server.MinecraftServer server, EjectEndpoint endpoint) {
        var level = server.getLevel(endpoint.hostDimension());
        return level != null ? level.getBlockEntity(endpoint.hostPos()) : null;
    }

    public record EndpointLocation(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /** Internal service boundary implemented by {@code core.eject}. */
    public interface Runtime {
        EjectRegistration register(EjectEndpoint endpoint, EjectHostResolver resolver);

        void unregister(ResourceKey<Level> dimension, BlockPos pos, Direction face);

        List<EndpointLocation> unregisterAll(BlockEntity host);

        void setBypass(boolean bypass);

        boolean isBypassed();

        static Runtime unavailable() {
            return new Runtime() {
                @Override
                public EjectRegistration register(EjectEndpoint endpoint, EjectHostResolver resolver) {
                    return () -> {
                    };
                }

                @Override
                public void unregister(ResourceKey<Level> dimension, BlockPos pos, Direction face) {
                }

                @Override
                public List<EndpointLocation> unregisterAll(BlockEntity host) {
                    return List.of();
                }

                @Override
                public void setBypass(boolean bypass) {
                }

                @Override
                public boolean isBypassed() {
                    return false;
                }
            };
        }
    }
}
