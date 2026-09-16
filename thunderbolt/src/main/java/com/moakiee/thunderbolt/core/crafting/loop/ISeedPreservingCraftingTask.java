package com.moakiee.thunderbolt.core.crafting.loop;

import appeng.api.stacks.AEKey;
import java.util.Set;
import java.util.UUID;

/** Marks an expanded task whose dispatched inputs remain part of a closed-loop seed reserve. */
public interface ISeedPreservingCraftingTask {

    UUID reusableSeedGroupId();

    Set<AEKey> reusableSeedCycleKeys();

    boolean hasSingleSeedInputPerMember();
}
