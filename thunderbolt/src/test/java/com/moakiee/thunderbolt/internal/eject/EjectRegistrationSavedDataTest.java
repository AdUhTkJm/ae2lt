package com.moakiee.thunderbolt.core.eject;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

class EjectRegistrationSavedDataTest {
    @Test
    void roundTripsTheLegacyAe2ltSchema() {
        var overworld = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        var nether = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"));
        var expected = new EjectRegistrationSavedData.PersistentRegistration(
                overworld, new BlockPos(12, 64, -9), Direction.WEST,
                nether, new BlockPos(-42, 80, 7));

        var legacy = new EjectRegistrationSavedData();
        legacy.add(expected);
        CompoundTag encodedLegacyFile = legacy.save(new CompoundTag(), null);

        var decodedByThunderbolt = EjectRegistrationSavedData.load(encodedLegacyFile, null);
        assertEquals(java.util.List.of(expected), decodedByThunderbolt.getAll());

        CompoundTag encodedNewFile = decodedByThunderbolt.save(new CompoundTag(), null);
        assertEquals(java.util.List.of(expected),
                EjectRegistrationSavedData.load(encodedNewFile, null).getAll());
    }
}
