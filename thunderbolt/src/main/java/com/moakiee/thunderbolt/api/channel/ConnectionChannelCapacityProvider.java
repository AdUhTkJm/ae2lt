package com.moakiee.thunderbolt.api.channel;

import appeng.api.networking.pathing.ChannelMode;

/** Supplies the per-link capacity of a directionless or wireless AE2 grid connection. */
public interface ConnectionChannelCapacityProvider {

    int getConnectionChannelCapacity(ChannelMode mode);
}
