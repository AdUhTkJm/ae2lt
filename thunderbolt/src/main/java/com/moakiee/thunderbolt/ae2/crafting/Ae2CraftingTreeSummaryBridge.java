package com.moakiee.thunderbolt.ae2.crafting;

import java.lang.reflect.InvocationTargetException;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;

/** Keeps AE2 Crafting Tree's optional summary payload initialized on Thunderbolt summaries. */
final class Ae2CraftingTreeSummaryBridge {
    private static final String SUMMARY_VIEW = "com.neuvillette.ae2ct.api.ICraftingPlanSummary";
    private static final String RECIPE_HELPER = "com.neuvillette.ae2ct.api.RecipeHelper";

    private Ae2CraftingTreeSummaryBridge() {
    }

    static void attach(CraftingPlanSummary summary, ICraftingPlan plan) {
        ClassLoader loader = CraftingPlanSummary.class.getClassLoader();
        final Class<?> summaryView;
        final Class<?> recipeHelper;
        try {
            summaryView = Class.forName(SUMMARY_VIEW, false, loader);
            recipeHelper = Class.forName(RECIPE_HELPER, false, loader);
        } catch (ClassNotFoundException absent) {
            return;
        }
        if (!summaryView.isInstance(summary)) {
            return;
        }

        try {
            Object tree = recipeHelper
                    .getMethod("fromCraftingPlan", CraftingPlan.class)
                    .invoke(null, CraftingPlanSummaryAdapter.adapt(plan));
            summaryView.getMethod("setJob", recipeHelper).invoke(summary, tree);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    ? invocation.getCause()
                    : failure;
            throw new IllegalStateException(
                    "Failed to attach AE2 Crafting Tree data to a Thunderbolt plan summary", cause);
        }
    }
}
