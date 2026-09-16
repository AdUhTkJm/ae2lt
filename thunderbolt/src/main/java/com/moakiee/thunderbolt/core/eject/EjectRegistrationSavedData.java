package com.moakiee.thunderbolt.core.eject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** Internal persistence for {@code EjectCapabilityRegistry}. */
public final class EjectRegistrationSavedData extends SavedData {
    private static final String DATA_NAME = "thunderbolt_eject_registrations";
    private static final String LEGACY_DATA_NAME = "ae2lt_eject_registrations";
    private static final String TAG_ENTRIES = "Entries";
    private static final String TAG_LEGACY_MIGRATION_COMPLETE = "LegacyMigrationComplete";
    private static final String TAG_I_DIM = "IDim";
    private static final String TAG_I_POS = "IPos";
    private static final String TAG_I_FACE = "IFace";
    private static final String TAG_P_DIM = "PDim";
    private static final String TAG_P_POS = "PPos";

    public record PersistentRegistration(
            ResourceKey<Level> interceptDimension,
            BlockPos interceptPos,
            Direction interceptFace,
            ResourceKey<Level> hostDimension,
            BlockPos hostPos) {}

    private static final Factory<EjectRegistrationSavedData> FACTORY = new Factory<>(
            EjectRegistrationSavedData::new,
            EjectRegistrationSavedData::load,
            null);

    private final List<PersistentRegistration> entries = new ArrayList<>();
    private boolean legacyMigrationComplete;

    public static EjectRegistrationSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Imports the old AE2LT data file once without modifying it. The completion marker is persisted
     * even for an empty source so intentionally-cleared registrations are never resurrected later.
     */
    public void migrateLegacyIfNeeded(MinecraftServer server) {
        if (legacyMigrationComplete) return;
        var legacy = server.overworld().getDataStorage().computeIfAbsent(FACTORY, LEGACY_DATA_NAME);
        for (var registration : legacy.entries) {
            if (!entries.contains(registration)) entries.add(registration);
        }
        legacyMigrationComplete = true;
        setDirty();
    }

    public List<PersistentRegistration> getAll() {
        return Collections.unmodifiableList(entries);
    }

    public void add(PersistentRegistration registration) {
        entries.add(registration);
        setDirty();
    }

    public void removeByIntercept(ResourceKey<Level> dimension, BlockPos pos, Direction face) {
        long packedPos = pos.asLong();
        if (entries.removeIf(entry -> entry.interceptDimension().equals(dimension)
                && entry.interceptPos().asLong() == packedPos
                && entry.interceptFace() == face)) {
            setDirty();
        }
    }

    public void removeByHost(ResourceKey<Level> dimension, BlockPos pos) {
        if (entries.removeIf(entry -> entry.hostDimension().equals(dimension)
                && entry.hostPos().equals(pos))) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var entry : entries) {
            var encoded = new CompoundTag();
            encoded.putString(TAG_I_DIM, entry.interceptDimension().location().toString());
            encoded.putLong(TAG_I_POS, entry.interceptPos().asLong());
            encoded.putInt(TAG_I_FACE, entry.interceptFace().get3DDataValue());
            encoded.putString(TAG_P_DIM, entry.hostDimension().location().toString());
            encoded.putLong(TAG_P_POS, entry.hostPos().asLong());
            list.add(encoded);
        }
        tag.put(TAG_ENTRIES, list);
        tag.putBoolean(TAG_LEGACY_MIGRATION_COMPLETE, legacyMigrationComplete);
        return tag;
    }

    static EjectRegistrationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new EjectRegistrationSavedData();
        data.legacyMigrationComplete = tag.getBoolean(TAG_LEGACY_MIGRATION_COMPLETE);
        if (!tag.contains(TAG_ENTRIES, Tag.TAG_LIST)) return data;
        var list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var encoded = list.getCompound(i);
            data.entries.add(new PersistentRegistration(
                    dimension(encoded.getString(TAG_I_DIM)),
                    BlockPos.of(encoded.getLong(TAG_I_POS)),
                    Direction.from3DDataValue(encoded.getInt(TAG_I_FACE)),
                    dimension(encoded.getString(TAG_P_DIM)),
                    BlockPos.of(encoded.getLong(TAG_P_POS))));
        }
        return data;
    }

    private static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
    }
}
