package com.moakiee.thunderbolt.api.crafting;

import java.util.List;

import appeng.api.networking.IGridNodeService;
import net.minecraft.resources.ResourceLocation;

/** Minimal node service: provided algorithms, one current selection, and its player priority. */
public interface CraftingAlgorithmProvider extends IGridNodeService {
    /**
     * The primary private algorithm owned by this node. This remains the compatibility fallback and
     * does not change with the GUI selection.
     */
    ResourceLocation getProvidedAlgorithm();

    /**
     * All private algorithms owned by this node, in display order. Existing single-algorithm
     * providers inherit a singleton list automatically.
     */
    default List<ResourceLocation> getProvidedAlgorithms() {
        return List.of(getProvidedAlgorithm());
    }

    ResourceLocation getSelectedAlgorithm();

    int getPriority();

    default boolean canSelectAlgorithm(ResourceLocation algorithmId) {
        return getProvidedAlgorithms().contains(algorithmId)
                || CraftingPlanningEngines.isPublic(algorithmId);
    }

    default CraftingAlgorithmSelection snapshot() {
        return new CraftingAlgorithmSelection(getSelectedAlgorithm(), getPriority());
    }
}
