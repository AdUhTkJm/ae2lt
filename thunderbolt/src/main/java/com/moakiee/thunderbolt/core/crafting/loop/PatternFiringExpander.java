package com.moakiee.thunderbolt.core.crafting.loop;

import appeng.api.crafting.IPatternDetails;
import java.util.Map;

/** Expands one contracted pattern firing count into concrete member-pattern firing counts. */
public interface PatternFiringExpander {

    Map<IPatternDetails, Long> expandPatternFirings(long macroFirings);
}
