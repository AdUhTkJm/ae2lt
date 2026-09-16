package com.moakiee.thunderbolt.api.crafting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Process-wide engine registry. Registration is only allowed during mod initialization. */
public final class CraftingPlanningEngines {
    /** Public sentinel used when a provider explicitly selects AE2's native planner. */
    public static final ResourceLocation VANILLA_ID = ResourceLocation.fromNamespaceAndPath(
            "ae2", "vanilla");
    /** Display-only sentinel for a fail-closed result after every candidate failed. */
    public static final ResourceLocation ALL_FAILED_ID = ResourceLocation.fromNamespaceAndPath(
            "thunderbolt", "all_failed");

    private static final Map<ResourceLocation, CraftingPlanningEngineDescriptor> ENGINES =
            new LinkedHashMap<>();
    private static volatile List<CraftingPlanningEngineDescriptor> ordered = List.of();
    private static volatile List<ResourceLocation> publicIds = List.of(VANILLA_ID);

    private CraftingPlanningEngines() {
    }

    /**
     * Registers an implementation and the author's declared algorithm priority/availability.
     * Public algorithms participate without a node provider; private algorithms require one.
     */
    public static synchronized void register(
            CraftingPlanningEngine engine, int algorithmPriority, boolean publicAlgorithm) {
        Objects.requireNonNull(engine, "engine");
        if (VANILLA_ID.equals(engine.id()) || ALL_FAILED_ID.equals(engine.id())) {
            throw new IllegalArgumentException(engine.id() + " is a reserved planning sentinel");
        }
        var descriptor = new CraftingPlanningEngineDescriptor(
                engine, algorithmPriority, publicAlgorithm);
        var previous = ENGINES.putIfAbsent(engine.id(), descriptor);
        if (previous != null
                && (previous.engine() != engine
                || previous.algorithmPriority() != algorithmPriority
                || previous.publicAlgorithm() != publicAlgorithm)) {
            throw new IllegalArgumentException(
                    "Crafting planning engine id already has another registration: " + engine.id());
        }
        var copy = new ArrayList<>(ENGINES.values());
        copy.sort(Comparator.comparingInt(CraftingPlanningEngineDescriptor::algorithmPriority)
                .reversed()
                .thenComparing(entry -> entry.id().toString()));
        ordered = List.copyOf(copy);
        var newPublicIds = copy.stream()
                .filter(CraftingPlanningEngineDescriptor::publicAlgorithm)
                .map(CraftingPlanningEngineDescriptor::id)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        newPublicIds.add(VANILLA_ID);
        publicIds = List.copyOf(newPublicIds);
    }

    @Nullable
    public static CraftingPlanningEngine get(ResourceLocation id) {
        var descriptor = ENGINES.get(id);
        return descriptor == null ? null : descriptor.engine();
    }

    @Nullable
    public static CraftingPlanningEngineDescriptor descriptor(ResourceLocation id) {
        return ENGINES.get(id);
    }

    /** All registered implementations, ordered by declared algorithm priority. */
    public static List<CraftingPlanningEngineDescriptor> ordered() {
        return ordered;
    }

    /** All selectable IDs for the default provider GUI, including vanilla. */
    public static List<ResourceLocation> allIds() {
        var result = new ArrayList<ResourceLocation>(ordered.size() + 1);
        ordered.forEach(entry -> result.add(entry.id()));
        result.add(VANILLA_ID);
        return List.copyOf(result);
    }

    /** Public algorithms plus the one private algorithm owned by a provider node. */
    public static List<ResourceLocation> selectableFor(ResourceLocation providedAlgorithm) {
        return selectableFor(List.of(Objects.requireNonNull(providedAlgorithm, "providedAlgorithm")));
    }

    /** Public algorithms plus all registered private algorithms owned by a provider node. */
    public static List<ResourceLocation> selectableFor(
            Collection<ResourceLocation> providedAlgorithms) {
        Objects.requireNonNull(providedAlgorithms, "providedAlgorithms");
        var provided = new LinkedHashSet<ResourceLocation>();
        for (var algorithm : providedAlgorithms) {
            provided.add(Objects.requireNonNull(algorithm, "providedAlgorithm"));
        }
        if (provided.isEmpty()) {
            throw new IllegalArgumentException("A crafting algorithm provider must own an algorithm");
        }
        var result = new ArrayList<ResourceLocation>(ordered.size() + 1);
        for (var descriptor : ordered) {
            if (descriptor.publicAlgorithm() || provided.contains(descriptor.id())) {
                result.add(descriptor.id());
            }
        }
        // Preserve the legacy single-provider behavior: its primary ID remains visible while its
        // implementation is temporarily unavailable. Additional IDs from a multi-provider are
        // shown only after registration, so optional algorithms disabled by config stay hidden.
        if (provided.size() == 1) {
            var primary = provided.getFirst();
            if (!VANILLA_ID.equals(primary) && !result.contains(primary)) {
                result.addFirst(primary);
            }
        }
        result.add(VANILLA_ID);
        return List.copyOf(result);
    }

    /** Public algorithms that do not require a provider node, including vanilla. */
    public static List<ResourceLocation> getPublic() {
        return publicIds;
    }

    public static boolean isPublic(ResourceLocation id) {
        if (VANILLA_ID.equals(id)) {
            return true;
        }
        var descriptor = ENGINES.get(id);
        return descriptor != null && descriptor.publicAlgorithm();
    }

    public static int algorithmPriority(ResourceLocation id) {
        if (VANILLA_ID.equals(id)) {
            return Integer.MIN_VALUE;
        }
        var descriptor = ENGINES.get(id);
        return descriptor == null ? Integer.MIN_VALUE : descriptor.algorithmPriority();
    }

    /** Player-facing name, with useful fallbacks for vanilla and unavailable registrations. */
    public static Component getName(ResourceLocation id) {
        if (ALL_FAILED_ID.equals(id)) {
            return Component.translatable("algorithm.thunderbolt.all_failed");
        }
        if (VANILLA_ID.equals(id)) {
            return Component.translatable("algorithm.thunderbolt.ae2_vanilla");
        }
        var descriptor = ENGINES.get(id);
        return descriptor == null ? Component.literal(id.toString()) : descriptor.engine().getName();
    }

    public static boolean isKnown(ResourceLocation id) {
        return VANILLA_ID.equals(id) || ENGINES.containsKey(id);
    }
}
