package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.assembly.FlightTerminalMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class FlightTerminalScreen extends AbstractContainerScreen<FlightTerminalMenu> {
    private static final String KEY = "terminal.blockspaceprogram.";
    private static final int ROWS = 5;
    private final Button[] rows = new Button[ROWS];
    private Button previous;
    private Button next;
    private Button unbind;
    private int page;

    public FlightTerminalScreen(FlightTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 250;
        imageHeight = 176;
    }

    @Override
    protected void init() {
        super.init();
        for (int row = 0; row < ROWS; row++) {
            int index = row;
            rows[row] = addRenderableWidget(Button.builder(Component.empty(), button -> send(page * ROWS + index + 1))
                    .bounds(leftPos + 8, topPos + 34 + 22 * row, imageWidth - 16, 20).build());
        }
        previous = addRenderableWidget(Button.builder(Component.translatable(KEY + "previous"), button -> {
            page--;
            updateButtons();
        }).bounds(leftPos + 8, topPos + 148, 60, 20).build());
        next = addRenderableWidget(Button.builder(Component.translatable(KEY + "next"), button -> {
            page++;
            updateButtons();
        }).bounds(leftPos + imageWidth - 68, topPos + 148, 60, 20).build());
        unbind = addRenderableWidget(Button.builder(Component.translatable(KEY + "unbind"), button -> send(0))
                .bounds(leftPos + 76, topPos + 148, imageWidth - 152, 20).build());
        updateButtons();
    }

    private void send(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButtons();
    }

    private void updateButtons() {
        int start = page * ROWS;
        int selected = menu.selection();
        var targets = menu.targets();
        for (int row = 0; row < ROWS; row++) {
            int index = start + row;
            var button = rows[row];
            button.visible = index < targets.size();
            if (button.visible) {
                var target = targets.get(index);
                var pos = target.position();
                button.setMessage(Component.translatable(KEY + "target", target.shortId(), pos.getX(), pos.getY(), pos.getZ()));
                button.active = selected != index + 1;
            }
        }
        previous.active = page > 0;
        next.active = start + ROWS < targets.size();
        unbind.active = selected != 0;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF242B33);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xFF657687);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 8, 0xFFFFFF, false);
        int selected = menu.selection();
        var status = selected > 0 ? Component.translatable(KEY + "bound", menu.targets().get(selected - 1).shortId())
                : Component.translatable(KEY + (selected < 0 ? "bound_unavailable" : "unbound"));
        graphics.drawString(font, status, 8, 21, 0xCFDAE3, false);
        if (menu.targets().isEmpty()) {
            graphics.drawString(font, Component.translatable(KEY + "no_ships"), 8, 54, 0xCFDAE3, false);
        }
    }
}
