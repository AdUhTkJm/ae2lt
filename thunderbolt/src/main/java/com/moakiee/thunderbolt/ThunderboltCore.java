package com.moakiee.thunderbolt;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import appeng.api.networking.GridServices;
import appeng.api.storage.StorageCells;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.api.crafting.ICraftingPlanningService;
import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.config.ThunderboltCommonConfig;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingPlanningService;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltMenus;
import com.moakiee.thunderbolt.core.crafting.planner.CpSatPlanningEngine;
import com.moakiee.thunderbolt.core.crafting.planner.ThunderboltV2PlanningEngine;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;
import com.moakiee.thunderbolt.core.eject.ThunderboltBlockEntities;
import com.moakiee.thunderbolt.core.storage.cell.IndexedCellStorageRegistry;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorageCellHandler;

/** Entry point for Thunderbolt Core's shared AE2 optimization and extension layer. */
@Mod(ThunderboltCore.MODID)
public final class ThunderboltCore {
    public static final String MODID = "thunderbolt";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ThunderboltCore(IEventBus modEventBus, ModContainer modContainer) {
        EjectCapabilityRegistry.installRuntime(EjectEndpointIndex.INSTANCE);
        ThunderboltBlockEntities.TYPES.register(modEventBus);
        ThunderboltMenus.TYPES.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        modContainer.registerConfig(
                ModConfig.Type.COMMON, ThunderboltCommonConfig.SPEC, "thunderbolt-common.toml");
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("[Thunderbolt Core] initialized");
    }

    private void onServerStarting(ServerStartingEvent event) {
        EjectEndpointIndex.INSTANCE.onServerStart(event.getServer());
        IndexedCellStorageRegistry.get(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        EjectEndpointIndex.INSTANCE.onServerStop();
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            StorageCells.addCellHandler(IndexedStorageCellHandler.INSTANCE);
            GridServices.register(ICraftingPlanningService.class, CraftingPlanningService.class);
            CraftingPlanningEngines.register(
                    ThunderboltV2PlanningEngine.INSTANCE, 1_000, false);
            if (ThunderboltCommonConfig.enableCpSatPlanner()) {
                LOGGER.info("[Thunderbolt Core] CP-SAT enabled; preparing native runtime");
                var cacheRoot = FMLPaths.GAMEDIR.get()
                        .resolve(".cache")
                        .resolve(MODID)
                        .resolve("cp-sat");
                if (CpSatPlanningEngine.INSTANCE.initialize(cacheRoot)) {
                    CraftingPlanningEngines.register(
                            CpSatPlanningEngine.INSTANCE, 900, false);
                    LOGGER.info("[Thunderbolt Core] CP-SAT planner ready");
                } else {
                    LOGGER.warn(
                            "[Thunderbolt Core] CP-SAT native runtime unavailable; "
                                    + "continuing without the CP-SAT planner",
                            CpSatPlanningEngine.INSTANCE.availabilityFailure());
                }
            }
        });
    }
}
