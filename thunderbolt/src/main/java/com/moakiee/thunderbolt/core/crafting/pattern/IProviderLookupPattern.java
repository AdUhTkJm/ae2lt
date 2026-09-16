package com.moakiee.thunderbolt.core.crafting.pattern;

import appeng.api.crafting.IPatternDetails;

/**
 * Optional contract for execution wrappers whose provider is registered under another pattern.
 *
 * <p>The wrapper itself remains the task identity, while provider discovery follows this delegate.
 * This prevents two macro expansions of the same physical recipe from being merged accidentally.
 */
public interface IProviderLookupPattern {
    IPatternDetails providerLookupPattern();
}
