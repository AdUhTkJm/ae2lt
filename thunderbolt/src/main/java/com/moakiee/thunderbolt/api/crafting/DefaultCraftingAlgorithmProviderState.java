package com.moakiee.thunderbolt.api.crafting;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * Reusable mutable provider implementation with a compact NBT codec. A host block entity normally
 * registers this object on its managed grid node and calls {@link #writeToNBT}/{@link #readFromNBT}
 * from its own persistence hooks.
 */
public final class DefaultCraftingAlgorithmProviderState
        implements ConfigurableCraftingAlgorithmProvider {
    private static final String TAG_ALGORITHM = "Algorithm";
    private static final String TAG_PRIORITY = "Priority";

    private final ResourceLocation providedAlgorithm;
    private final List<ResourceLocation> providedAlgorithms;
    private final CraftingAlgorithmSelection defaultSelection;
    private final Runnable changedCallback;
    private CraftingAlgorithmSelection selection;

    public DefaultCraftingAlgorithmProviderState(
            ResourceLocation defaultAlgorithm, int defaultPriority, Runnable changedCallback) {
        this(defaultAlgorithm, List.of(defaultAlgorithm), defaultPriority, changedCallback);
    }

    public DefaultCraftingAlgorithmProviderState(
            ResourceLocation defaultAlgorithm,
            Collection<ResourceLocation> providedAlgorithms,
            int defaultPriority,
            Runnable changedCallback) {
        this.providedAlgorithm = Objects.requireNonNull(defaultAlgorithm, "defaultAlgorithm");
        Objects.requireNonNull(providedAlgorithms, "providedAlgorithms");
        var normalizedProvidedAlgorithms = new LinkedHashSet<ResourceLocation>();
        normalizedProvidedAlgorithms.add(this.providedAlgorithm);
        for (var algorithm : providedAlgorithms) {
            normalizedProvidedAlgorithms.add(Objects.requireNonNull(algorithm, "providedAlgorithm"));
        }
        this.providedAlgorithms = List.copyOf(normalizedProvidedAlgorithms);
        this.defaultSelection = new CraftingAlgorithmSelection(providedAlgorithm, defaultPriority);
        this.selection = defaultSelection;
        this.changedCallback = Objects.requireNonNull(changedCallback, "changedCallback");
    }

    @Override
    public ResourceLocation getProvidedAlgorithm() {
        return providedAlgorithm;
    }

    @Override
    public List<ResourceLocation> getProvidedAlgorithms() {
        return providedAlgorithms;
    }

    @Override
    public ResourceLocation getSelectedAlgorithm() {
        return selection.algorithmId();
    }

    @Override
    public int getPriority() {
        return selection.priority();
    }

    @Override
    public CraftingAlgorithmSelection snapshot() {
        return selection;
    }

    @Override
    public void setSelection(CraftingAlgorithmSelection selection) {
        var normalized = Objects.requireNonNull(selection, "selection");
        if (!canSelectAlgorithm(normalized.algorithmId())) {
            throw new IllegalArgumentException(
                    "Algorithm provider cannot select " + normalized.algorithmId());
        }
        if (!this.selection.equals(normalized)) {
            this.selection = normalized;
            changedCallback.run();
        }
    }

    public void writeToNBT(CompoundTag tag) {
        tag.putString(TAG_ALGORITHM, selection.algorithmId().toString());
        tag.putInt(TAG_PRIORITY, selection.priority());
    }

    public void readFromNBT(CompoundTag tag) {
        var algorithm = ResourceLocation.tryParse(tag.getString(TAG_ALGORITHM));
        if (algorithm != null
                && CraftingPlanningEngines.isKnown(algorithm)
                && !canSelectAlgorithm(algorithm)) {
            algorithm = null;
        }
        int priority = tag.contains(TAG_PRIORITY) ? tag.getInt(TAG_PRIORITY) : defaultSelection.priority();
        selection = new CraftingAlgorithmSelection(
                algorithm == null ? defaultSelection.algorithmId() : algorithm,
                priority);
    }
}
