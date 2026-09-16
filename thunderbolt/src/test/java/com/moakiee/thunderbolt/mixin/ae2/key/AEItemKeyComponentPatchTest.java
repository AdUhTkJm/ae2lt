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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

class AEItemKeyComponentPatchTest {
    static {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ignoresDefaultItemComponents() throws Exception {
        var plain = new ItemStack(Items.STONE);

        assertFalse(plain.getComponents().isEmpty(),
                "vanilla defaults make the full component map unsuitable for this check");
        assertTrue(plain.isComponentsPatchEmpty());
        assertFalse(hasComponentPatch(plain));
    }

    @Test
    void detectsAddedComponentValues() throws Exception {
        var named = new ItemStack(Items.STONE);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("marked"));

        assertFalse(named.isComponentsPatchEmpty());
        assertTrue(hasComponentPatch(named));
    }

    @Test
    void detectsRemovedDefaultComponents() throws Exception {
        var changed = new ItemStack(Items.STONE);
        changed.remove(DataComponents.RARITY);

        assertFalse(changed.isComponentsPatchEmpty());
        assertTrue(hasComponentPatch(changed));
    }

    private static boolean hasComponentPatch(ItemStack stack) throws Exception {
        var method = AEItemKeyComponentsMixin.class.getDeclaredMethod(
                "thunderbolt$hasComponentPatch", ItemStack.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, stack);
    }
}
