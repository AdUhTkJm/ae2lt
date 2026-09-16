package com.moakiee.thunderbolt.api.crafting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/** Pure priority resolver shared by the AE2 grid service and tests. */
public final class CraftingAlgorithmResolver {
    /** Player priority used by a public algorithm when no provider explicitly selects it. */
    public static final int PUBLIC_DEFAULT_PRIORITY = 0;

    private CraftingAlgorithmResolver() {
    }

    public static List<PlanningChoice> resolve(
            Collection<CraftingAlgorithmSelection> providerSelections) {
        var selectedPriorities = new HashMap<ResourceLocation, Integer>();
        for (var selection : providerSelections) {
            if (selection != null && CraftingPlanningEngines.isKnown(selection.algorithmId())) {
                selectedPriorities.merge(selection.algorithmId(), selection.priority(), Math::max);
            }
        }

        var candidates = new HashMap<ResourceLocation, Candidate>();
        for (var id : CraftingPlanningEngines.getPublic()) {
            boolean explicitlySelected = selectedPriorities.containsKey(id);
            int playerPriority = explicitlySelected
                    ? selectedPriorities.get(id)
                    : PUBLIC_DEFAULT_PRIORITY;
            candidates.put(id, new Candidate(
                    id, playerPriority, CraftingPlanningEngines.algorithmPriority(id)));
        }
        for (Map.Entry<ResourceLocation, Integer> entry : selectedPriorities.entrySet()) {
            candidates.put(entry.getKey(), new Candidate(
                    entry.getKey(), entry.getValue(),
                    CraftingPlanningEngines.algorithmPriority(entry.getKey())));
        }

        var ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator.comparingInt(Candidate::playerPriority).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::algorithmPriority).reversed())
                .thenComparing(entry -> entry.id().toString()));

        var result = new ArrayList<PlanningChoice>(ordered.size());
        for (var candidate : ordered) {
            if (CraftingPlanningEngines.VANILLA_ID.equals(candidate.id())) {
                result.add(PlanningChoice.VANILLA);
                return List.copyOf(result);
            }
            result.add(PlanningChoice.engine(candidate.id()));
        }
        // Defensive only: the registry always exposes vanilla as a public sentinel.
        result.add(PlanningChoice.VANILLA);
        return List.copyOf(result);
    }

    private record Candidate(
            ResourceLocation id, int playerPriority, int algorithmPriority) {
    }
}
