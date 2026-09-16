package com.moakiee.thunderbolt.api.eject;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

class EjectCapabilityRegistryContractTest {

    @Test
    void registrationIsASafeNoOpBeforeTheCoreRuntimeIsInstalled() {
        var endpoint = new EjectEndpoint(
                Level.OVERWORLD,
                new BlockPos(1, 2, 3),
                Direction.NORTH,
                Level.OVERWORLD,
                new BlockPos(4, 5, 6),
                EjectOfflinePolicy.REJECT);

        var registration = EjectCapabilityRegistry.register(endpoint, (server, ignored) -> null);
        assertDoesNotThrow(registration::close);
        assertDoesNotThrow(registration::close);
        assertFalse(EjectCapabilityRegistry.isBypassed());
    }
}
