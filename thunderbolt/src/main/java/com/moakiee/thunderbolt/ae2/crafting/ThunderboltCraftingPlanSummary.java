package com.moakiee.thunderbolt.ae2.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;

/** Builds confirmation summaries for plans produced by Thunderbolt-proxied planning engines. */
public final class ThunderboltCraftingPlanSummary {
    private ThunderboltCraftingPlanSummary() {
    }

    /** Native AE2 plans retain AE2's native summary path; every proxy engine uses this one. */
    public static boolean handles(@Nullable ResourceLocation selectedAlgorithm) {
        return selectedAlgorithm != null
                && !CraftingPlanningEngines.VANILLA_ID.equals(selectedAlgorithm);
    }

    /**
     * Builds a summary from the inventory split already captured by the completed plan.
     *
     * <p>Unlike AE2's native summary factory, this does not query the live network after planning.
     * {@link ICraftingPlan#usedItems()} is the amount obtained from the planning snapshot and
     * {@link ICraftingPlan#missingItems()} is its shortfall. Keeping that split makes the displayed
     * result describe the same inventory state that the proxied algorithm actually planned.
     */
    public static CraftingPlanSummary fromPlan(ICraftingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        var stats = new HashMap<AEKey, KeyStats>();

        for (var entry : plan.usedItems()) {
            mapping(stats, entry.getKey()).stored += entry.getLongValue();
        }
        for (var entry : plan.missingItems()) {
            var keyStats = mapping(stats, entry.getKey());
            if (plan.simulation()) {
                keyStats.missing += entry.getLongValue();
            } else {
                keyStats.stored += entry.getLongValue();
            }
        }
        for (var entry : plan.emittedItems()) {
            var keyStats = mapping(stats, entry.getKey());
            keyStats.stored += entry.getLongValue();
            keyStats.crafting += entry.getLongValue();
        }
        for (var patternEntry : plan.patternTimes().entrySet()) {
            for (var output : patternEntry.getKey().getOutputs()) {
                mapping(stats, output.what()).crafting +=
                        output.amount() * patternEntry.getValue();
            }
        }

        var entries = new ArrayList<CraftingPlanSummaryEntry>(stats.size());
        for (var entry : stats.entrySet()) {
            var value = entry.getValue();
            entries.add(new CraftingPlanSummaryEntry(
                    entry.getKey(), value.missing, value.stored, value.crafting));
        }
        Collections.sort(entries);

        var summary = new CraftingPlanSummary(
                plan.bytes(), plan.simulation(), List.copyOf(entries));
        Ae2CraftingTreeSummaryBridge.attach(summary, plan);
        return summary;
    }

    private static KeyStats mapping(Map<AEKey, KeyStats> stats, AEKey key) {
        return stats.computeIfAbsent(
                Objects.requireNonNull(key, "plan key"), ignored -> new KeyStats());
    }

    private static final class KeyStats {
        private long missing;
        private long stored;
        private long crafting;
    }
}
