package com.moakiee.thunderbolt.core.eject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.api.eject.EjectEndpoint;
import com.moakiee.thunderbolt.api.eject.EjectHostResolver;
import com.moakiee.thunderbolt.api.eject.EjectOfflinePolicy;
import com.moakiee.thunderbolt.api.eject.EjectRegistration;
import com.moakiee.thunderbolt.core.eject.EjectRegistrationSavedData;
import com.moakiee.thunderbolt.core.eject.ThunderboltGhostOutputBlockEntity;

/** Runtime endpoint index and persistence-backed default implementation. */
public final class EjectEndpointIndex implements EjectCapabilityRegistry.Runtime {

    public static final EjectEndpointIndex INSTANCE = new EjectEndpointIndex();

    private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<EnumMap<Direction, List<Entry>>>>
            registrations = new IdentityHashMap<>();
    private final ThreadLocal<int[]> bypassDepth = ThreadLocal.withInitial(() -> new int[1]);

    @Nullable
    private MinecraftServer server;
    @Nullable
    private EjectRegistrationSavedData savedData;

    private EjectEndpointIndex() {
    }

    public void onServerStart(MinecraftServer server) {
        this.server = server;
        this.savedData = EjectRegistrationSavedData.get(server);
        this.savedData.migrateLegacyIfNeeded(server);
        registrations.clear();
        bypassDepth.remove();
        for (var persisted : this.savedData.getAll()) {
            var endpoint = new EjectEndpoint(
                    persisted.interceptDimension(), persisted.interceptPos(), persisted.interceptFace(),
                    persisted.hostDimension(), persisted.hostPos(), EjectOfflinePolicy.REJECT);
            add(endpoint, EjectEndpointIndex::resolveStableHost, false);
        }
    }

    public void onServerStop() {
        server = null;
        savedData = null;
        registrations.clear();
        bypassDepth.remove();
    }

    @Override
    public EjectRegistration register(EjectEndpoint endpoint, EjectHostResolver resolver) {
        var entry = add(endpoint, resolver, true);
        var open = new AtomicBoolean(true);
        return () -> {
            if (open.compareAndSet(true, false)) {
                removeEntry(entry, true);
            }
        };
    }

    @Override
    public void unregister(ResourceKey<Level> dimension, BlockPos pos, Direction face) {
        var dimensionMap = registrations.get(dimension);
        if (dimensionMap == null) return;
        var faceMap = dimensionMap.get(pos.asLong());
        if (faceMap == null) return;
        faceMap.remove(face);
        if (faceMap.isEmpty()) {
            dimensionMap.remove(pos.asLong());
            if (dimensionMap.isEmpty()) registrations.remove(dimension);
        }
        if (savedData != null) {
            savedData.removeByIntercept(dimension, pos, face);
        }
    }

    @Override
    public List<EjectCapabilityRegistry.EndpointLocation> unregisterAll(BlockEntity host) {
        var hostLevel = host.getLevel();
        if (hostLevel != null && hostLevel.isClientSide()) return List.of();
        var hostDimension = hostLevel != null ? hostLevel.dimension() : null;
        var hostPos = host.getBlockPos();
        var removed = new ArrayList<EjectCapabilityRegistry.EndpointLocation>();

        for (var dimensionIterator = registrations.entrySet().iterator(); dimensionIterator.hasNext();) {
            var dimensionEntry = dimensionIterator.next();
            var positionIterator = dimensionEntry.getValue().long2ObjectEntrySet().iterator();
            while (positionIterator.hasNext()) {
                var positionEntry = positionIterator.next();
                var faceMap = positionEntry.getValue();
                boolean changed = false;
                for (var faceIterator = faceMap.entrySet().iterator(); faceIterator.hasNext();) {
                    var entries = faceIterator.next().getValue();
                    if (entries.removeIf(entry -> hostDimension != null
                            && entry.endpoint.hostDimension().equals(hostDimension)
                            && entry.endpoint.hostPos().equals(hostPos))) {
                        changed = true;
                    }
                    if (entries.isEmpty()) faceIterator.remove();
                }
                if (changed) {
                    removed.add(new EjectCapabilityRegistry.EndpointLocation(
                            dimensionEntry.getKey(), BlockPos.of(positionEntry.getLongKey())));
                }
                if (faceMap.isEmpty()) positionIterator.remove();
            }
            if (dimensionEntry.getValue().isEmpty()) dimensionIterator.remove();
        }
        if (savedData != null && hostDimension != null) {
            savedData.removeByHost(hostDimension, hostPos);
        }
        return List.copyOf(removed);
    }

    @Override
    public void setBypass(boolean bypass) {
        int[] depth = bypassDepth.get();
        if (bypass) depth[0]++;
        else if (depth[0] > 0) depth[0]--;
    }

    @Override
    public boolean isBypassed() {
        return bypassDepth.get()[0] > 0;
    }

    public boolean isEmpty() {
        return registrations.isEmpty();
    }

    @Nullable
    public Entry lookupByFace(ResourceKey<Level> dimension, long pos, Direction face) {
        var dimensionMap = registrations.get(dimension);
        if (dimensionMap == null) return null;
        var faceMap = dimensionMap.get(pos);
        return faceMap != null ? preferResolved(faceMap.get(face)) : null;
    }

    @Nullable
    public Entry lookupAny(ResourceKey<Level> dimension, long pos) {
        var dimensionMap = registrations.get(dimension);
        if (dimensionMap == null) return null;
        var faceMap = dimensionMap.get(pos);
        if (faceMap == null) return null;
        Entry fallback = null;
        for (var entries : faceMap.values()) {
            var candidate = preferResolved(entries);
            if (candidate != null && resolveHost(candidate) != null) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    @Nullable
    public BlockEntity resolveHost(Entry entry) {
        var currentServer = server;
        return currentServer != null ? entry.resolver.resolve(currentServer, entry.endpoint) : null;
    }

    private Entry add(EjectEndpoint endpoint, EjectHostResolver resolver, boolean persist) {
        var targetLevel = server != null ? server.getLevel(endpoint.interceptDimension()) : null;
        var ghost = new ThunderboltGhostOutputBlockEntity(endpoint.interceptPos());
        if (targetLevel != null) ghost.setLevel(targetLevel);
        var entry = new Entry(endpoint, resolver, ghost);
        registrations
                .computeIfAbsent(endpoint.interceptDimension(), ignored -> new Long2ObjectOpenHashMap<>())
                .computeIfAbsent(endpoint.interceptPos().asLong(), ignored -> new EnumMap<>(Direction.class))
                .computeIfAbsent(endpoint.interceptFace(), ignored -> new ArrayList<>())
                .add(entry);
        if (persist && savedData != null) {
            savedData.add(new EjectRegistrationSavedData.PersistentRegistration(
                    endpoint.interceptDimension(), endpoint.interceptPos(), endpoint.interceptFace(),
                    endpoint.hostDimension(), endpoint.hostPos()));
        }
        return entry;
    }

    private void removeEntry(Entry target, boolean persist) {
        var endpoint = target.endpoint;
        var dimensionMap = registrations.get(endpoint.interceptDimension());
        if (dimensionMap == null) return;
        var faceMap = dimensionMap.get(endpoint.interceptPos().asLong());
        if (faceMap == null) return;
        var entries = faceMap.get(endpoint.interceptFace());
        if (entries != null) entries.remove(target);
        if (entries != null && entries.isEmpty()) faceMap.remove(endpoint.interceptFace());
        if (faceMap.isEmpty()) dimensionMap.remove(endpoint.interceptPos().asLong());
        if (dimensionMap.isEmpty()) registrations.remove(endpoint.interceptDimension());
        if (persist && savedData != null) {
            savedData.removeByIntercept(
                    endpoint.interceptDimension(), endpoint.interceptPos(), endpoint.interceptFace());
        }
    }

    @Nullable
    private Entry preferResolved(@Nullable List<Entry> entries) {
        if (entries == null) return null;
        Entry fallback = null;
        for (var entry : entries) {
            if (resolveHost(entry) != null) return entry;
            if (fallback == null) fallback = entry;
        }
        return fallback;
    }

    private static BlockEntity resolveStableHost(MinecraftServer server, EjectEndpoint endpoint) {
        var level = server.getLevel(endpoint.hostDimension());
        return level != null ? level.getBlockEntity(endpoint.hostPos()) : null;
    }

    public record Entry(
            EjectEndpoint endpoint,
            EjectHostResolver resolver,
            BlockEntity ghostBlockEntity) {
    }
}
