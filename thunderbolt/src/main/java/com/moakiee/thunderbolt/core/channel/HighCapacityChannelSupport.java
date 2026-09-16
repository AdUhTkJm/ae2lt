package com.moakiee.thunderbolt.core.channel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.blockentity.networking.ControllerBlockEntity;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.api.channel.HighCapacityChannelOwner;

/**
 * Centralized owner checks for the high-capacity channel subsystem.
 * Any grid-node owner implementing {@link HighCapacityChannelOwner} is
 * automatically granted elevated channel capacity by the grid mixins.
 */
public final class HighCapacityChannelSupport {
    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Set<String> LOGGED_OWNER_LOOKUP_FAILURES =
            Collections.synchronizedSet(new java.util.HashSet<>());

    private HighCapacityChannelSupport() {
    }

    public static boolean is128ChannelOwner(@Nullable Object owner) {
        return owner instanceof HighCapacityChannelOwner;
    }

    public static boolean is128ChannelConnection(@Nullable Object ownerA, @Nullable Object ownerB) {
        return is128ChannelOwner(ownerA) && is128ChannelOwner(ownerB);
    }

    public static int channelsPerController() {
        return CoreConfig.channelsPerController();
    }

    public static int supplyPerController(int cableCapacityFactor) {
        long supply = (long) channelsPerController() * Math.max(1, cableCapacityFactor);
        return (int) Math.min(Integer.MAX_VALUE / 2, supply);
    }

    public static @Nullable Object tryGetOwner(IGridNode node) {
        try {
            return node.getOwner();
        } catch (RuntimeException exception) {
            String key = node.getClass().getName() + ":" + exception.getClass().getName();
            if (LOGGED_OWNER_LOOKUP_FAILURES.add(key)) {
                LOG.warn("Thunderbolt failed to read grid node owner from {}.", node.getClass().getName(), exception);
            }
            return null;
        }
    }


    /**
     * Returns ALL controller nodes in the grid, including subclasses.
     * AE2's {@code getMachineNodes(Class)} uses exact class matching
     * ({@code owner.getClass()}), so subclasses of {@code ControllerBlockEntity}
     * must be queried by their concrete class. This method scans all registered
     * machine classes and collects those assignable to {@code ControllerBlockEntity}.
     */
    public static List<IGridNode> getAllControllerNodes(IGrid grid) {
        List<IGridNode> all = new ArrayList<>();
        for (var clazz : grid.getMachineClasses()) {
            if (ControllerBlockEntity.class.isAssignableFrom(clazz)) {
                for (var node : grid.getMachineNodes(clazz)) {
                    all.add(node);
                }
            }
        }
        return all;
    }

    /**
     * @return total channel capacity for an high-capacity network,
     *         or 0 if no high-capacity controllers are present / channel mode is INFINITE.
     */
    public static int calculateHighCapacityNetworkCapacity(IGrid grid) {
        int sourceCount = 0;
        for (var node : getAllControllerNodes(grid)) {
            if (ChannelSourceRegistry.isChannelSource(node.getOwner())) {
                sourceCount++;
            }
        }
        if (sourceCount == 0) {
            return 0;
        }

        var channelMode = grid.getPathingService().getChannelMode();
        if (channelMode == ChannelMode.INFINITE) {
            return 0;
        }

        long capacity = (long) channelsPerController() * sourceCount * channelMode.getCableCapacityFactor();
        return (int) Math.min(Integer.MAX_VALUE, capacity);
    }

    /**
     * Counts channel-consuming devices in the grid, treating each multiblock
     * cluster as a single device. Required because our PathingCalculation mixin
     * calls incrementChannelCount(1) on every multiblock sibling (to keep
     * isActive() true across the cluster), which would otherwise inflate any
     * naive count of meetsChannelRequirements() nodes by N for an N-block cluster.
     */
    public static int countUsedChannels(IGrid grid) {
        Set<IGridNode> visitedMultiblockSiblings = new ReferenceOpenHashSet<>();
        int count = 0;
        for (var node : grid.getNodes()) {
            if (!node.hasFlag(GridFlags.REQUIRE_CHANNEL)) continue;
            if (!node.meetsChannelRequirements()) continue;
            if (node.hasFlag(GridFlags.MULTIBLOCK)) {
                var mb = node.getService(IGridMultiblock.class);
                if (mb != null) {
                    if (!visitedMultiblockSiblings.add(node)) continue;
                    var siblings = mb.getMultiblockNodes();
                    while (siblings.hasNext()) {
                        var sibling = siblings.next();
                        if (sibling != node) {
                            visitedMultiblockSiblings.add(sibling);
                        }
                    }
                }
            }
            count++;
        }
        return count;
    }

}
