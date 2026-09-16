package com.moakiee.thunderbolt.mixin.ae2.key;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.fluids.FluidStack;

class AEFluidKeyComponentPatchTest {
    static {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ignoresPlainFluids() throws Exception {
        var plain = new FluidStack(Fluids.WATER, 1000);

        assertTrue(plain.getComponents().isEmpty());
        assertTrue(plain.isComponentsPatchEmpty());
        assertFalse(hasComponentPatch(plain));
    }

    @Test
    void detectsAddedComponentValues() throws Exception {
        var named = new FluidStack(Fluids.WATER, 1000);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("marked"));

        assertFalse(named.isComponentsPatchEmpty());
        assertTrue(hasComponentPatch(named));
    }

    private static boolean hasComponentPatch(FluidStack stack) throws Exception {
        var method = AEFluidKeyComponentsMixin.class.getDeclaredMethod(
                "thunderbolt$hasComponentPatch", FluidStack.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, stack);
    }
}
