package com.moakiee.thunderbolt.core.crafting.algorithm.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import appeng.client.gui.style.StyleManager;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltMenus;
import com.moakiee.thunderbolt.core.crafting.algorithm.menu.CraftingAlgorithmProviderMenu;

@EventBusSubscriber(
        modid = ThunderboltCore.MODID,
        value = Dist.CLIENT)
public final class ThunderboltClientEvents {
    private ThunderboltClientEvents() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(
                ThunderboltMenus.CRAFTING_ALGORITHM_PROVIDER.get(),
                ThunderboltClientEvents::createCraftingAlgorithmProviderScreen);
    }

    private static CraftingAlgorithmProviderScreen createCraftingAlgorithmProviderScreen(
            CraftingAlgorithmProviderMenu menu, Inventory inventory, Component title) {
        var style = StyleManager.loadStyleDoc(
                "/screens/thunderbolt/crafting_algorithm_provider.json");
        return new CraftingAlgorithmProviderScreen(menu, inventory, title, style);
    }
}
