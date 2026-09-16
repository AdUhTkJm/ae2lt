package com.moakiee.thunderbolt.core.crafting.algorithm.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.implementations.AESubScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.TabButton;
import appeng.core.network.serverbound.SwitchGuisPacket;
import appeng.menu.implementations.PriorityMenu;

import com.moakiee.thunderbolt.core.crafting.algorithm.menu.CraftingAlgorithmProviderMenu;

/**
 * Secondary provider screen for selecting the crafting planning algorithm. Driven by the AE2 screen-style system
 * (see assets/ae2/screens/thunderbolt/crafting_algorithm_provider.json) so it visually matches native AE2 dialogs.
 */
public final class CraftingAlgorithmProviderScreen
        extends AEBaseScreen<CraftingAlgorithmProviderMenu> {

    /** AE2 text-field sprite, reused as the recessed display behind the selected algorithm name. */
    private static final Blitter NAME_FIELD = Blitter.texture("guis/text_field.png", 128, 128);
    /** Vertical offset of the non-editable field variant inside text_field.png. */
    private static final int FIELD_VARIANT_V = 12;
    private static final int FIELD_HEIGHT = 12;

    // Selector row layout in dialog-local coordinates, kept in sync with the style JSON.
    private static final int FIELD_LEFT = 34;
    private static final int FIELD_RIGHT = 166;
    private static final int FIELD_TOP = 39;
    private static final int FIELD_PADDING = 4;
    /** Never shrink the algorithm name below this factor; longer names are ellipsized instead. */
    private static final float MIN_NAME_SCALE = 0.6f;
    private static final String ELLIPSIS = "…";

    public CraftingAlgorithmProviderScreen(
            CraftingAlgorithmProviderMenu menu, Inventory inventory, Component title, ScreenStyle style) {
        super(menu, inventory, title, style);

        // Keep the host-provided dialog title instead of a hardcoded label
        setTextContent(TEXT_ID_DIALOG_TITLE, title);

        widgets.add("previousAlgorithm", new TabButton(
                Icon.ARROW_LEFT,
                Component.translatable("gui.thunderbolt.algorithm_provider.previous"),
                ignored -> sendButton(CraftingAlgorithmProviderMenu.PREVIOUS_ALGORITHM)));
        widgets.add("nextAlgorithm", new TabButton(
                Icon.ARROW_RIGHT,
                Component.translatable("gui.thunderbolt.algorithm_provider.next"),
                ignored -> sendButton(CraftingAlgorithmProviderMenu.NEXT_ALGORITHM)));

        AESubScreen.addBackButton(menu, "back", widgets);

        widgets.add("openPriority", new TabButton(
                Icon.PRIORITY,
                Component.translatable("gui.thunderbolt.algorithm_provider.selection_priority"),
                ignored -> PacketDistributor.sendToServer(
                        SwitchGuisPacket.openSubMenu(PriorityMenu.TYPE))));
    }

    private void sendButton(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        super.drawBG(guiGraphics, offsetX, offsetY, mouseX, mouseY, partialTicks);

        // Recessed display field behind the algorithm name, tiled from AE2's text-field sprite.
        // The sprite band is only 128px wide, so the (uniform) inner column is stretched to fill
        // fields wider than that, otherwise a gap would show between the middle and the right cap.
        int left = offsetX + FIELD_LEFT;
        int right = offsetX + FIELD_RIGHT;
        int top = offsetY + FIELD_TOP;
        NAME_FIELD.src(0, FIELD_VARIANT_V, 1, FIELD_HEIGHT).dest(left, top).blit(guiGraphics);
        NAME_FIELD.src(1, FIELD_VARIANT_V, 1, FIELD_HEIGHT)
                .dest(left + 1, top, right - left - 2, FIELD_HEIGHT)
                .blit(guiGraphics);
        NAME_FIELD.src(127, FIELD_VARIANT_V, 1, FIELD_HEIGHT).dest(right - 1, top).blit(guiGraphics);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        // Note: the pose is already translated by (leftPos, topPos) here, so draw in dialog-local coordinates
        var name = menu.selectedAlgorithmName().getString();
        int maxWidth = FIELD_RIGHT - FIELD_LEFT - 2 * FIELD_PADDING;
        int color = style.getColor(PaletteColor.TEXTFIELD_TEXT).toARGB();

        // Shrink to fit; if even the minimum scale is not enough, ellipsize the text instead
        float scale = Math.min(1.0f, (float) maxWidth / font.width(name));
        if (scale < MIN_NAME_SCALE) {
            scale = MIN_NAME_SCALE;
            int avail = (int) (maxWidth / scale) - font.width(ELLIPSIS);
            name = font.plainSubstrByWidth(name, avail) + ELLIPSIS;
        }

        int centerX = (FIELD_LEFT + FIELD_RIGHT) / 2;
        // Keep the (possibly scaled) name vertically centered inside the 12px field
        float textY = FIELD_TOP + 2 + (font.lineHeight * (1.0f - scale)) / 2.0f;

        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(centerX, textY, 0);
        pose.scale(scale, scale, 1.0f);
        guiGraphics.drawCenteredString(font, name, 0, 0, color);
        pose.popPose();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();

        setTextContent("availability", Component.translatable(menu.selectedAlgorithmIsPublic()
                ? "gui.thunderbolt.algorithm_provider.public"
                : "gui.thunderbolt.algorithm_provider.provider_required"));
        setTextContent("algorithm_priority", algorithmPriorityText());
        setTextContent("player_priority", Component.translatable(
                "gui.thunderbolt.algorithm_provider.player_priority", menu.priority()));
    }

    private Component algorithmPriorityText() {
        if (menu.selectedAlgorithmIsVanilla()) {
            return Component.translatable(
                    "gui.thunderbolt.algorithm_provider.algorithm_priority.vanilla");
        } else if (menu.selectedAlgorithmIsKnown()) {
            return Component.translatable(
                    "gui.thunderbolt.algorithm_provider.algorithm_priority",
                    menu.selectedAlgorithmPriority());
        }
        return Component.translatable(
                "gui.thunderbolt.algorithm_provider.algorithm_priority.unknown");
    }
}
