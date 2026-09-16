package com.moakiee.thunderbolt.api.crafting;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/** Atomic snapshot of one provider node's current player-configured selection. */
public record CraftingAlgorithmSelection(ResourceLocation algorithmId, int priority) {
    public CraftingAlgorithmSelection {
        Objects.requireNonNull(algorithmId, "algorithmId");
    }
}
