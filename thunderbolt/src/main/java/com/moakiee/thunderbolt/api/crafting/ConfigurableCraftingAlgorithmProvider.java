package com.moakiee.thunderbolt.api.crafting;

/** Optional extension used by Thunderbolt's default provider submenu. */
public interface ConfigurableCraftingAlgorithmProvider extends CraftingAlgorithmProvider {
    void setSelection(CraftingAlgorithmSelection selection);
}
