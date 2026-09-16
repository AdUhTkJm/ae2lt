package com.moakiee.thunderbolt.core.crafting.algorithm;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.core.crafting.algorithm.menu.CraftingAlgorithmProviderMenu;

public final class ThunderboltMenus {
    public static final DeferredRegister<MenuType<?>> TYPES =
            DeferredRegister.create(Registries.MENU, ThunderboltCore.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<CraftingAlgorithmProviderMenu>>
            CRAFTING_ALGORITHM_PROVIDER = TYPES.register(
                    "crafting_algorithm_provider",
                    () -> CraftingAlgorithmProviderMenu.TYPE);

    private ThunderboltMenus() {
    }
}
