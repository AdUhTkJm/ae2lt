package com.moakiee.thunderbolt.api.channel;

/** Optional capability exposed by a grid-node owner that needs multiple channels. */
public interface ChannelRequestProvider {
    default int thunderbolt$getRequestedChannels() { return 1; }
    default void thunderbolt$setUsedChannels(int channels) { }
}
