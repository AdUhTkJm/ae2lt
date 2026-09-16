package com.moakiee.thunderbolt.core.crafting.cpu;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

/** Identity index for nodes that can dynamically expose an extended crafting CPU cluster. */
public final class DynamicCraftingCpuClusterIndex<P, C> {
    private final Set<P> providers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<C> clusters = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<C> clustersView = Collections.unmodifiableSet(clusters);
    private final Set<C> refreshedClusters = Collections.newSetFromMap(new IdentityHashMap<>());

    public boolean addProvider(P provider) {
        return providers.add(provider);
    }

    public boolean removeProvider(P provider) {
        return providers.remove(provider);
    }

    public void replaceProviders(Iterable<? extends P> replacements) {
        providers.clear();
        for (var provider : replacements) {
            providers.add(provider);
        }
    }

    public boolean refresh(
            Function<? super P, @Nullable ? extends C> resolver,
            Consumer<? super C> onAdded) {
        refreshedClusters.clear();
        for (var provider : providers) {
            var cluster = resolver.apply(provider);
            if (cluster != null) {
                refreshedClusters.add(cluster);
            }
        }

        boolean changed = !clusters.equals(refreshedClusters);
        clusters.removeIf(cluster -> !refreshedClusters.contains(cluster));
        for (var cluster : refreshedClusters) {
            if (clusters.add(cluster)) {
                onAdded.accept(cluster);
            }
        }
        return changed;
    }

    public Set<C> clusters() {
        return clustersView;
    }
}
