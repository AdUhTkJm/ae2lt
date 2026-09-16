package com.moakiee.thunderbolt.api.crafting;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

/** One entry in an ordered crafting-planner policy. */
public record PlanningChoice(Kind kind, @Nullable ResourceLocation engineId) {
    public enum Kind {
        VANILLA,
        ENGINE
    }

    public static final PlanningChoice VANILLA = new PlanningChoice(Kind.VANILLA, null);

    public PlanningChoice {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.ENGINE) != (engineId != null)) {
            throw new IllegalArgumentException("ENGINE requires an id; VANILLA must not have one");
        }
    }

    public static PlanningChoice engine(ResourceLocation id) {
        return new PlanningChoice(Kind.ENGINE, Objects.requireNonNull(id, "id"));
    }

    public String serializedName() {
        return kind == Kind.ENGINE ? "engine:" + engineId : kind.name().toLowerCase();
    }

    public static PlanningChoice parse(String value) {
        if ("vanilla".equals(value)) {
            return VANILLA;
        }
        if (value.startsWith("engine:")) {
            return engine(ResourceLocation.parse(value.substring("engine:".length())));
        }
        throw new IllegalArgumentException("Unknown planning choice: " + value);
    }
}
