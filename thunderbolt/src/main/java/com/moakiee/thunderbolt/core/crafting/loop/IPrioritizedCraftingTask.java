package com.moakiee.thunderbolt.core.crafting.loop;

/** Optional scheduling order for execution tasks whose safe progress depends on ordering. */
public interface IPrioritizedCraftingTask {

    int dispatchPriority();

    int dispatchOrder();
}
