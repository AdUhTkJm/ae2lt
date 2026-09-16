package com.moakiee.thunderbolt.core.crafting.loop;

import appeng.api.stacks.AEKey;
import java.util.Map;

/** Identifies the logical reusable seed selected for each execution input slot. */
public interface IPlannedSeedSlotPattern {

    Map<Integer, AEKey> plannedSeedInputSlots();
}
