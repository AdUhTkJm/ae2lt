package com.moakiee.thunderbolt.api.crafting;

import java.util.Map;
import java.util.Objects;

/** Immutable, engine-neutral progress snapshot exposed to planning diagnostics. */
public record PlanningDiagnosticSnapshot(String phase, Map<String, Long> metrics) {
    public PlanningDiagnosticSnapshot {
        Objects.requireNonNull(phase, "phase");
        metrics = Map.copyOf(Objects.requireNonNull(metrics, "metrics"));
    }

    public static PlanningDiagnosticSnapshot phase(String phase) {
        return new PlanningDiagnosticSnapshot(phase, Map.of());
    }
}
