package com.moakiee.thunderbolt.api.crafting.batch;

/** Controls how the AE2 crafting CPU accounts for a provider's accepted copy count. */
public enum BatchDispatchMode {
    /** Copy count is bounded by the crafting CPU's ordinary per-tick operation budget. */
    NORMAL,
    /**
     * The provider may consume the remaining copy budget for one successful operation.
     * Input extraction, energy, provider capacity and the finite CPU budget still apply.
     */
    UNBOUNDED
}
