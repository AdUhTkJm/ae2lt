package com.moakiee.thunderbolt.core.crafting.algorithm;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridServiceProvider;
import net.minecraft.nbt.CompoundTag;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.CraftingAlgorithmProvider;
import com.moakiee.thunderbolt.api.crafting.CraftingAlgorithmResolver;
import com.moakiee.thunderbolt.api.crafting.CraftingAlgorithmSelection;
import com.moakiee.thunderbolt.api.crafting.ICraftingPlanningService;
import com.moakiee.thunderbolt.api.crafting.PlanningChoice;

/** Default AE2 grid resolver. It stores node references, never replicated network policy state. */
public final class CraftingPlanningService implements ICraftingPlanningService, IGridServiceProvider {
    private final IGrid grid;
    private final Map<IGridNode, CraftingAlgorithmProvider> providers = new IdentityHashMap<>();

    public CraftingPlanningService(IGrid grid) {
        this.grid = Objects.requireNonNull(grid, "grid");
    }

    @Override
    public List<PlanningChoice> resolve() {
        var selections = new ArrayList<CraftingAlgorithmSelection>(providers.size());
        for (var entry : providers.entrySet()) {
            if (!entry.getKey().isOnline()) {
                continue;
            }
            try {
                var provider = entry.getValue();
                var selection = provider.snapshot();
                if (!provider.canSelectAlgorithm(selection.algorithmId())) {
                    ThunderboltCore.LOGGER.warn(
                            "[Thunderbolt Core] ignored algorithm {} selected by provider for {}",
                            selection.algorithmId(), provider.getProvidedAlgorithms());
                    continue;
                }
                selections.add(selection);
            } catch (RuntimeException failure) {
                ThunderboltCore.LOGGER.warn(
                        "[Thunderbolt Core] ignored invalid crafting algorithm provider on grid {}",
                        grid, failure);
            }
        }
        return CraftingAlgorithmResolver.resolve(selections);
    }

    @Override
    public void addNode(IGridNode node, CompoundTag savedData) {
        var provider = node.getService(CraftingAlgorithmProvider.class);
        if (provider != null) {
            providers.put(node, provider);
        }
    }

    @Override
    public void removeNode(IGridNode node) {
        providers.remove(node);
    }
}
