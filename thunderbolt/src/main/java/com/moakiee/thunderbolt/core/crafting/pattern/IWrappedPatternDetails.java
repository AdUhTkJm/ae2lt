package com.moakiee.thunderbolt.core.crafting.pattern;

import appeng.api.crafting.IPatternDetails;

/** Exposes the pattern wrapped by an execution or metadata adapter. */
public interface IWrappedPatternDetails {

    IPatternDetails wrappedPatternDetails();
}
