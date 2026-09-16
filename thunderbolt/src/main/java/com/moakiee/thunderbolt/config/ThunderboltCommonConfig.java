package com.moakiee.thunderbolt.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.api.channel.ChannelRequestProvider;
import com.moakiee.thunderbolt.api.channel.HighCapacityChannelOwner;
import com.moakiee.thunderbolt.core.channel.HighCapacityChannelSupport;

/** Common configuration for optional Thunderbolt runtime components. */
public final class ThunderboltCommonConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLE_CP_SAT_PLANNER;
    private static final ModConfigSpec.EnumValue<ChannelMode> CHANNEL_MODE;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("planning");
        ENABLE_CP_SAT_PLANNER = builder
                .comment(
                        "Enable the experimental OR-Tools CP-SAT crafting planner.",
                        "When enabled, Thunderbolt downloads and verifies the matching native runtime",
                        "during startup. A download or load failure only disables this planner for that run.")
                .define("enableCpSatPlanner", false);
        builder.pop();
        builder.push("channel");
        CHANNEL_MODE = builder.comment("When to enable Thunderbolt channel max-flow.")
                .defineEnum("mode", ChannelMode.MOD);
        builder.pop();
        SPEC = builder.build();
    }

    private ThunderboltCommonConfig() {
    }

    public static boolean enableCpSatPlanner() {
        return ENABLE_CP_SAT_PLANNER.get();
    }

    public static boolean useMaxFlow(IGrid grid, boolean hasControllers) {
        if (!hasControllers) return false;
        return switch (CHANNEL_MODE.get()) {
            case ON -> true;
            case MOD -> CoreConfig.channelMaxFlowRequired();
            case DEVICE -> hasOptInOwner(grid)
                    || HighCapacityChannelSupport.getAllControllerNodes(grid).stream()
                            .anyMatch(node -> ChannelSourceRegistry.isChannelSource(node.getOwner()));
        };
    }

    private static boolean hasOptInOwner(IGrid grid) {
        for (IGridNode node : grid.getNodes()) {
            Object owner = node.getOwner();
            if (owner instanceof ChannelRequestProvider || owner instanceof HighCapacityChannelOwner) {
                return true;
            }
        }
        return false;
    }

    public enum ChannelMode { MOD, DEVICE, ON }
}
