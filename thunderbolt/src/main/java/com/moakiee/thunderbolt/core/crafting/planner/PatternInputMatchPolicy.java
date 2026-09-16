package com.moakiee.thunderbolt.core.crafting.planner;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/** Shared containment rules for connecting a producer output to a pattern input. */
final class PatternInputMatchPolicy {

    private PatternInputMatchPolicy() {
    }

    /**
     * Strict outputs may feed exact and same-id inputs. A late-bound same-id output may only feed a
     * same-id input because its runtime secondary state is not known during planning.
     */
    static boolean accepts(
            GenericStack[] possibleInputs,
            boolean inputSameId,
            AEKey output,
            boolean outputSameId) {
        if (possibleInputs == null || output == null || outputSameId && !inputSameId) {
            return false;
        }
        for (var possible : possibleInputs) {
            if (possible == null || possible.what() == null) {
                continue;
            }
            if (inputSameId
                    ? samePrimaryIdentity(possible.what(), output)
                    : possible.what().equals(output)) {
                return true;
            }
        }
        return false;
    }

    static boolean samePrimaryIdentity(AEKey left, AEKey right) {
        return left != null && right != null
                && left.dropSecondary().equals(right.dropSecondary());
    }
}
