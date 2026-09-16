package com.moakiee.thunderbolt.core.crafting.support;

import appeng.api.crafting.IPatternDetails;
import com.moakiee.thunderbolt.core.crafting.pattern.IBatchExecutionPattern;
import com.moakiee.thunderbolt.core.crafting.pattern.IProviderLookupPattern;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Resolves task wrappers independently for physical execution and provider registration. */
public final class CraftingPatternDelegates {
    public static IPatternDetails forBatchExecution(IPatternDetails details) {
        Objects.requireNonNull(details, "details");
        var visited = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        var current = details;
        while (current instanceof IBatchExecutionPattern wrapped) {
            if (!visited.add(current)) {
                throw new IllegalStateException("cyclic batch execution pattern delegation");
            }
            var next = Objects.requireNonNull(
                    wrapped.batchExecutionPattern(), "batchExecutionPattern");
            if (next == current) {
                throw new IllegalStateException("batch execution pattern delegates to itself");
            }
            current = next;
        }
        return current;
    }

    public static IPatternDetails forProviderLookup(IPatternDetails details) {
        Objects.requireNonNull(details, "details");
        var visited = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        var current = details;
        while (current instanceof IProviderLookupPattern wrapped) {
            if (!visited.add(current)) {
                throw new IllegalStateException("cyclic provider lookup pattern delegation");
            }
            var next = Objects.requireNonNull(
                    wrapped.providerLookupPattern(), "providerLookupPattern");
            if (next == current) {
                throw new IllegalStateException("provider lookup pattern delegates to itself");
            }
            current = next;
        }
        return current;
    }

    private CraftingPatternDelegates() { }
}
