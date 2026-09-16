package com.moakiee.thunderbolt.api.eject;

/** Behavior of a projected endpoint while its real host cannot be resolved. */
public enum EjectOfflinePolicy {
    /** Expose rejecting item/fluid handlers so automation cannot claim successful insertion. */
    REJECT,
    /** Expose no projected capability. */
    ABSENT
}
