package com.moakiee.thunderbolt.core.crafting.loop;

import appeng.api.stacks.AEItemKey;

/** Supplies a decodable pattern definition for persistence of an execution-only wrapper. */
public interface CraftingTaskPersistenceDefinition {

    AEItemKey craftingTaskPersistenceDefinition();
}
