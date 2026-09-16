package com.moakiee.thunderbolt.core.crafting.algorithm.menu;

import appeng.helpers.IPriorityHost;
import net.minecraft.network.chat.Component;

import com.moakiee.thunderbolt.api.crafting.ConfigurableCraftingAlgorithmProvider;

/** Host contract for Thunderbolt's AE2-native algorithm selection submenu. */
public interface CraftingAlgorithmProviderMenuHost
        extends ConfigurableCraftingAlgorithmProvider, IPriorityHost {
    @Override
    default int getPriority() {
        return snapshot().priority();
    }

    @Override
    default void setPriority(int priority) {
        var current = snapshot();
        setSelection(new com.moakiee.thunderbolt.api.crafting.CraftingAlgorithmSelection(
                current.algorithmId(), priority));
    }

    default Component getCraftingAlgorithmMenuTitle() {
        return Component.translatable("gui.thunderbolt.algorithm_provider.title");
    }
}
