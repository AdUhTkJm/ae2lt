package com.moakiee.thunderbolt.core.crafting.algorithm.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.implementations.MenuTypeBuilder;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.CraftingAlgorithmSelection;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;

/** Default server-authoritative submenu for a configurable algorithm provider. */
public final class CraftingAlgorithmProviderMenu extends AEBaseMenu implements ISubMenu {
    public static final MenuType<CraftingAlgorithmProviderMenu> TYPE = MenuTypeBuilder
            .create(CraftingAlgorithmProviderMenu::new, CraftingAlgorithmProviderMenuHost.class)
            .withMenuTitle(CraftingAlgorithmProviderMenuHost::getCraftingAlgorithmMenuTitle)
            .withInitialData(
                    CraftingAlgorithmProviderMenu::writeInitialData,
                    (host, menu, buf) -> menu.readInitialData(buf))
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(
                    ThunderboltCore.MODID, "crafting_algorithm_provider"));

    public static final int PREVIOUS_ALGORITHM = 0;
    public static final int NEXT_ALGORITHM = 1;
    private static final int MAX_ALGORITHMS = 256;

    private List<ResourceLocation> algorithms;
    private final CraftingAlgorithmProviderMenuHost host;
    private int selectedIndex;
    private int priority;

    public CraftingAlgorithmProviderMenu(
            int id, Inventory inventory, CraftingAlgorithmProviderMenuHost host) {
        super(TYPE, id, inventory, host);
        this.host = host;
        var current = host.snapshot();
        this.algorithms = selectableAlgorithms(host);
        this.selectedIndex = selectedIndex(algorithms, current.algorithmId(),
                host.getProvidedAlgorithm());
        this.priority = current.priority();
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return CraftingAlgorithmProviderMenu.this.selectedIndex;
            }

            @Override
            public void set(int value) {
                CraftingAlgorithmProviderMenu.this.selectedIndex = Math.floorMod(value, algorithms.size());
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return CraftingAlgorithmProviderMenu.this.priority;
            }

            @Override
            public void set(int value) {
                CraftingAlgorithmProviderMenu.this.priority = value;
            }
        });
    }

    private static void writeInitialData(
            CraftingAlgorithmProviderMenuHost host, FriendlyByteBuf buf) {
        var current = host.snapshot();
        var algorithms = selectableAlgorithms(host);
        buf.writeVarInt(algorithms.size());
        algorithms.forEach(buf::writeResourceLocation);
        buf.writeVarInt(selectedIndex(
                algorithms, current.algorithmId(), host.getProvidedAlgorithm()));
        buf.writeInt(current.priority());
    }

    private void readInitialData(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count <= 0 || count > MAX_ALGORITHMS) {
            throw new IllegalArgumentException("Invalid crafting algorithm count " + count);
        }
        var algorithms = new ArrayList<ResourceLocation>(count);
        for (int i = 0; i < count; i++) {
            algorithms.add(buf.readResourceLocation());
        }
        this.algorithms = List.copyOf(algorithms);
        selectedIndex = Math.floorMod(buf.readVarInt(), algorithms.size());
        priority = buf.readInt();
    }

    private static List<ResourceLocation> selectableAlgorithms(
            CraftingAlgorithmProviderMenuHost host) {
        return CraftingPlanningEngines.selectableFor(host.getProvidedAlgorithms());
    }

    private static int selectedIndex(
            List<ResourceLocation> algorithms,
            ResourceLocation selected,
            ResourceLocation provided) {
        int index = algorithms.indexOf(selected);
        if (index < 0) {
            index = algorithms.indexOf(provided);
        }
        return Math.max(index, 0);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!stillValid(player)) {
            return false;
        }
        switch (id) {
            case PREVIOUS_ALGORITHM -> selectedIndex = Math.floorMod(selectedIndex - 1, algorithms.size());
            case NEXT_ALGORITHM -> selectedIndex = Math.floorMod(selectedIndex + 1, algorithms.size());
            default -> {
                return false;
            }
        }
        host.setSelection(new CraftingAlgorithmSelection(selectedAlgorithm(), priority));
        broadcastChanges();
        return true;
    }

    public ResourceLocation selectedAlgorithm() {
        return algorithms.get(selectedIndex);
    }

    public int priority() {
        return priority;
    }

    public Component selectedAlgorithmName() {
        return CraftingPlanningEngines.getName(selectedAlgorithm());
    }

    /** Author-declared baseline priority for the currently selected algorithm. */
    public int selectedAlgorithmPriority() {
        return CraftingPlanningEngines.algorithmPriority(selectedAlgorithm());
    }

    public boolean selectedAlgorithmIsKnown() {
        return CraftingPlanningEngines.isKnown(selectedAlgorithm());
    }

    public boolean selectedAlgorithmIsVanilla() {
        return CraftingPlanningEngines.VANILLA_ID.equals(selectedAlgorithm());
    }

    public boolean selectedAlgorithmIsPublic() {
        return CraftingPlanningEngines.isPublic(selectedAlgorithm());
    }

    @Override
    public CraftingAlgorithmProviderMenuHost getHost() {
        return host;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

}
