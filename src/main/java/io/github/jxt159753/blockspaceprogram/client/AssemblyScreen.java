package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyMenu;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyNetworking;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyNetworking.Action;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.github.jxt159753.blockspaceprogram.assembly.ShipPlacement;
import io.github.jxt159753.blockspaceprogram.assembly.StagePerformance;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

final class AssemblyScreen extends Screen implements MenuAccess<AssemblyMenu> {
    private static final int LEFT = 10;
    private static final int TOOLS_Y = 27;
    private static final int VIEW_TOP = 51;
    private static final int INVENTORY_COLUMNS = 12;
    private static final int SLOT = 18;
    private static final int CHIP = 15;
    private static final int STATS = 35;
    private static final int WHITE = 0xFFF1F5FB;
    private static final int MUTED = 0xFFAEBECF;

    private final AssemblyMenu menu;
    private AssemblyViewport viewport;
    private int stageLeft;
    private int viewBottom;
    private int inventoryTop;
    private int stageScroll;
    private int selectedSlot = -1;
    private boolean stackMode = true;
    private boolean orbiting;
    private int sourceIndex;
    private int turns;
    private int symmetry = 1;
    private Button modeButton;
    private Button sourceButton;
    private Button turnButton;
    private Button symmetryButton;
    private Button addStage;
    private Button removeStage;
    private Button save;
    private Button load;
    private Button assemble;
    private ShipDesign shownDesign;
    private double wetMass;
    private List<StageCard> cards = List.of();
    private int stageContentHeight;
    @Nullable
    private StageAction dragged;
    private int dragRevision;
    @Nullable
    private AssemblyViewport.Hit hover;

    AssemblyScreen(AssemblyMenu menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
    }

    @Override
    public AssemblyMenu getMenu() {
        return menu;
    }

    @Override
    protected void init() {
        stageLeft = width - LEFT - Mth.clamp(width / 3, 126, 204);
        inventoryTop = height - LEFT - SLOT * 3;
        viewBottom = menu.isWorldAssembly() ? height - LEFT - 35 : inventoryTop - 22;
        int viewWidth = stageLeft - LEFT - 6;
        viewport = new AssemblyViewport(LEFT, VIEW_TOP, viewWidth, viewBottom - VIEW_TOP);
        addStage = button(width - LEFT - 39, TOOLS_Y, 18, "plus", "add_stage", () -> edit(Action.ADD_STAGE, -1, -1));
        removeStage = button(width - LEFT - 19, TOOLS_Y, 19, "minus", "remove_stage", () -> edit(Action.REMOVE_STAGE, -1, -1));
        shownDesign = null;
        if (menu.isWorldAssembly()) {
            assemble = button(LEFT, height - LEFT - 20, viewWidth, "world_assemble", "world_assemble_tip",
                    () -> edit(Action.ASSEMBLE, -1, -1));
            return;
        }

        int toolWidth = (viewWidth - 6) / 4;
        modeButton = button(LEFT, TOOLS_Y, toolWidth, "stack", "mode_tip", () -> {
            stackMode = !stackMode;
            sourceIndex = 0;
        });
        sourceButton = button(LEFT + toolWidth + 2, TOOLS_Y, toolWidth, "source_button", "source_tip", () -> sourceIndex++);
        turnButton = button(LEFT + (toolWidth + 2) * 2, TOOLS_Y, toolWidth, "turn_button", "turn_tip", () -> turns = (turns + 1) % 4);
        symmetryButton = button(LEFT + (toolWidth + 2) * 3, TOOLS_Y, toolWidth, "symmetry_button", "symmetry_tip",
                () -> symmetry = symmetry == 4 ? 1 : symmetry * 2);
        int blueprintX = Math.max(LEFT + INVENTORY_COLUMNS * SLOT + 8, stageLeft);
        save = button(blueprintX, inventoryTop, width - LEFT - blueprintX, "save", "save_tip", () -> edit(Action.SAVE, selectedSlot, -1));
        load = button(blueprintX, inventoryTop + 24, width - LEFT - blueprintX, "load", "load_tip", () -> edit(Action.LOAD, selectedSlot, -1));
    }

    private Button button(int x, int y, int width, String label, String tooltip, Runnable action) {
        return addRenderableWidget(Button.builder(text(label), button -> action.run()).bounds(x, y, width, 20)
                .tooltip(Tooltip.create(text(tooltip))).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ShipDesign design = menu.design();
        if (design != shownDesign) {
            updateStages(design);
        }
        BakedPart part = selectedPart();
        var source = source(part);
        boolean world = menu.isWorldAssembly();
        var frame = viewport.frame(design);
        hover = viewport.pick(frame, design, mouseX, mouseY);
        var request = world ? null : request(design, frame, hover, source);
        var preview = request == null || part == null ? null : ShipPlacement.place(design, part, request);

        graphics.fill(0, 0, width, height, 0xF4111721);
        graphics.fill(0, 0, width, 24, 0xFF263247);
        graphics.drawString(font, title, LEFT, 8, WHITE);
        Component count = text("parts", design.nodes().size(), ShipDesign.MAX_PARTS);
        graphics.drawString(font, count, width - LEFT - font.width(count), 8, MUTED);
        graphics.drawString(font, text("stages"), stageLeft + 1, TOOLS_Y + 6, WHITE);
        viewport.render(graphics, frame, design, preview == null ? design : preview.design(), hover, !world && stackMode);
        if (design.nodes().isEmpty() && !world) {
            graphics.drawCenteredString(font, text("first_part"), (LEFT + stageLeft - 6) / 2, (VIEW_TOP + viewBottom) / 2, WHITE);
        } else if (hover != null) {
            graphics.drawString(font, text("part_number", hover.node() + 1), LEFT + 5, VIEW_TOP + 5, WHITE);
            if (preview != null && preview.error() == null && request.partMarker() >= 0) {
                var connector = part.connectors().get(request.partMarker());
                graphics.drawString(font, text("preview_size", connector.width() * part.scale(),
                        connector.height() * part.scale()), LEFT + 5, VIEW_TOP + 16, WHITE);
            }
        }
        drawStatus(graphics, preview);
        drawStages(graphics, mouseY);
        if (world) {
            graphics.drawString(font, text("world_mass", value(wetMass, 1)), LEFT, TOOLS_Y + 6, WHITE);
            assemble.active = !design.nodes().isEmpty();
        } else {
            drawInventory(graphics, mouseX, mouseY);
            modeButton.setMessage(text(stackMode ? "stack" : "surface"));
            if (source == null) {
                sourceButton.setMessage(text("no_source"));
            } else {
                var direction = Component.translatable("direction.blockspaceprogram.short." + source.normal().getName());
                if (source.marker() >= 0) {
                    direction.append(" #" + (source.marker() + 1));
                }
                sourceButton.setMessage(direction);
            }
            sourceButton.active = source != null;
            turnButton.setMessage(Component.literal(turns * 90 + "°"));
            symmetryButton.setMessage(Component.literal(symmetry + "×"));
            updateBlueprintButtons();
        }
        addStage.active = (!world || !design.nodes().isEmpty()) && design.stageCount() < ShipDesign.MAX_STAGES;
        removeStage.active = design.stageCount() > 1 && design.lastStageEmpty();
        super.render(graphics, mouseX, mouseY, partialTick);

        if (dragged != null) {
            Component label = dragged.label();
            graphics.fill(mouseX + 3, mouseY + 2, mouseX + font.width(label) + 11, mouseY + 16, 0xFF446187);
            graphics.drawString(font, label, mouseX + 7, mouseY + 5, WHITE);
        } else {
            int slot = slotAt(mouseX, mouseY);
            if (slot >= 0 && !menu.getSlot(slot).getItem().isEmpty()) {
                graphics.renderTooltip(font, menu.getSlot(slot).getItem(), mouseX, mouseY);
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    private void drawStatus(GuiGraphics graphics, @Nullable ShipPlacement.Result preview) {
        Component status;
        int color = MUTED;
        if (preview != null && preview.error() != null && !menu.design().nodes().isEmpty()) {
            status = text(preview.error());
            color = 0xFFFFC28A;
        } else if (!menu.message().isEmpty()) {
            status = text(menu.message(), menu.messageCount());
        } else {
            status = text(menu.isWorldAssembly() ? "world_camera_tip" : "camera_tip");
        }
        int statusWidth = (menu.isWorldAssembly() ? width - LEFT : stageLeft - 6) - LEFT;
        graphics.drawString(font, font.plainSubstrByWidth(status.getString(), statusWidth), LEFT, viewBottom + 2, color);
        if (!menu.isWorldAssembly()) {
            graphics.drawString(font, text("inventory"), LEFT, inventoryTop - 11, MUTED);
        }
    }

    private void updateStages(ShipDesign design) {
        shownDesign = design;
        wetMass = design.nodes().stream().mapToDouble(node -> {
            var properties = node.part().properties();
            return properties.mass() + properties.fuelCapacity().values().stream().mapToDouble(Double::doubleValue).sum();
        }).sum();
        var metrics = StagePerformance.calculate(design);
        List<List<StageAction>> actions = new ArrayList<>();
        for (int i = 0; i < design.stageCount(); i++) {
            actions.add(new ArrayList<>());
        }
        for (int node = 0; node < design.nodes().size(); node++) {
            int stage = design.nodes().get(node).engineStage();
            if (stage >= 0) {
                actions.get(stage).add(new StageAction(Action.MOVE_ENGINE, node, stage, text("engine_action", node + 1)));
            }
        }
        for (int link = 0; link < design.links().size(); link++) {
            var edge = design.links().get(link);
            if (edge.separable()) {
                int stage = edge.separationStage();
                actions.get(stage).add(new StageAction(Action.MOVE_SEPARATOR, link, stage, text("separator_action", edge.second().node() + 1)));
            }
        }
        List<StageCard> updated = new ArrayList<>();
        int y = 0;
        for (int stage = 0; stage < design.stageCount(); stage++) {
            var card = new StageCard(stage, y, metrics.get(stage), actions.get(stage));
            updated.add(card);
            y += card.height() + 4;
        }
        cards = updated;
        stageContentHeight = y;
        stageScroll = Mth.clamp(stageScroll, 0, maxScroll());
    }

    private void drawStages(GuiGraphics graphics, int mouseY) {
        int right = width - LEFT;
        graphics.fill(stageLeft, VIEW_TOP, right, viewBottom, 0xFF101925);
        graphics.enableScissor(stageLeft, VIEW_TOP, right, viewBottom);
        for (StageCard card : cards) {
            int y = VIEW_TOP + card.top() - stageScroll;
            boolean target = dragged != null && mouseY >= Math.max(VIEW_TOP, y) && mouseY < Math.min(viewBottom, y + card.height());
            graphics.fill(stageLeft, y, right, y + card.height(), target ? 0xFF365675 : 0xFF24354B);
            graphics.drawString(font, text("stage", card.stage() + 1), stageLeft + 5, y + 4, WHITE);
            graphics.drawString(font, text("delta_v", value(card.performance().deltaV(), 1)), stageLeft + 5, y + 15, 0xFFACDDFF);
            graphics.drawString(font, text("stage_stats", value(card.performance().thrustToWeight(), 2),
                    value(card.performance().burnTime(), 1)), stageLeft + 5, y + 25, MUTED);
            y += STATS;
            for (StageAction action : card.actions()) {
                graphics.fill(stageLeft + 3, y, right - 3, y + CHIP - 2,
                        action.action() == Action.MOVE_ENGINE ? 0xFF365371 : 0xFF714D39);
                graphics.drawString(font, action.label(), stageLeft + 7, y + 2, WHITE);
                y += CHIP;
            }
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int height = viewBottom - VIEW_TOP;
            int thumb = Math.max(8, height * height / stageContentHeight);
            int top = VIEW_TOP + (height - thumb) * stageScroll / maxScroll();
            graphics.fill(right - 2, top, right, top + thumb, 0xFF768AA4);
        }
    }

    private int maxScroll() {
        return Math.max(0, stageContentHeight - (viewBottom - VIEW_TOP));
    }

    private void drawInventory(GuiGraphics graphics, int mouseX, int mouseY) {
        int hovered = slotAt(mouseX, mouseY);
        for (int i = 0; i < menu.slots.size(); i++) {
            int x = LEFT + i % INVENTORY_COLUMNS * SLOT;
            int y = inventoryTop + i / INVENTORY_COLUMNS * SLOT;
            ItemStack stack = menu.getSlot(i).getItem();
            boolean usable = stack.is(BlockSpaceProgram.BAKED_PART.get()) || stack.is(BlockSpaceProgram.SHIP_BLUEPRINT.get());
            graphics.fill(x, y, x + SLOT - 1, y + SLOT - 1, usable ? 0xFF39516C : 0xFF243140);
            if (i == selectedSlot || i == hovered) {
                graphics.renderOutline(x, y, SLOT - 1, SLOT - 1, i == selectedSlot ? 0xFFF4CB79 : MUTED);
            }
            graphics.renderItem(stack, x + 1, y + 1);
            graphics.renderItemDecorations(font, stack, x + 1, y + 1);
        }
    }

    private int slotAt(double x, double y) {
        if (menu.isWorldAssembly() || x < LEFT || x >= LEFT + SLOT * INVENTORY_COLUMNS
                || y < inventoryTop || y >= inventoryTop + SLOT * 3) {
            return -1;
        }
        return ((int) y - inventoryTop) / SLOT * INVENTORY_COLUMNS + ((int) x - LEFT) / SLOT;
    }

    private ItemStack selectedStack() {
        return selectedSlot >= 0 ? menu.getSlot(selectedSlot).getItem() : ItemStack.EMPTY;
    }

    private void updateBlueprintButtons() {
        ItemStack selected = selectedStack();
        boolean blueprint = selected.is(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        boolean saved = blueprint && selected.has(BlockSpaceProgram.SHIP_DESIGN);
        save.active = blueprint && !saved && !menu.design().nodes().isEmpty();
        load.active = saved;
    }

    @Nullable
    private BakedPart selectedPart() {
        return selectedStack().get(BlockSpaceProgram.PART_DATA);
    }

    @Nullable
    private ShipPlacement.Source source(@Nullable BakedPart part) {
        if (part == null) {
            return null;
        }
        var sources = ShipPlacement.sources(part, stackMode);
        return sources.isEmpty() ? null : sources.get(Math.floorMod(sourceIndex, sources.size()));
    }

    @Nullable
    private ShipPlacement.Request request(ShipDesign design, AssemblyViewport.Frame frame, @Nullable AssemblyViewport.Hit hit,
            @Nullable ShipPlacement.Source source) {
        if (design.nodes().isEmpty()) {
            return new ShipPlacement.Request(-1, -1, -1, Direction.UP, Direction.DOWN, Vec3.ZERO, 0, 1);
        }
        if (hit == null || source == null) {
            return null;
        }
        int marker = stackMode ? viewport.stackMarker(design, hit, frame) : -1;
        if (stackMode && marker < 0) {
            return null;
        }
        return new ShipPlacement.Request(hit.node(), marker, source.marker(), hit.normal(), source.normal(), hit.position(), turns, symmetry);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int slot = slotAt(x, y);
            if (slot >= 0) {
                selectedSlot = slot;
                sourceIndex = 0;
                turns = 0;
                BakedPart part = selectedPart();
                if (part != null) {
                    stackMode = part.connectors().stream().anyMatch(connector -> connector.kind().isStack());
                }
                updateBlueprintButtons();
                return true;
            }
            StageCard card = stageAt(x, y);
            if (card != null) {
                int row = (int) y - (VIEW_TOP + card.top() - stageScroll) - STATS;
                if (row >= 0 && row / CHIP < card.actions().size()) {
                    dragged = card.actions().get(row / CHIP);
                    dragRevision = menu.revision();
                }
                return true;
            }
        }
        if (viewport.contains(x, y)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                orbiting = true;
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !menu.isWorldAssembly()) {
                ShipDesign design = menu.design();
                var frame = viewport.frame(design);
                var hit = viewport.pick(frame, design, x, y);
                if (hasShiftDown()) {
                    if (hit != null) {
                        edit(Action.REMOVE, hit.node(), -1);
                    }
                } else {
                    BakedPart part = selectedPart();
                    var request = request(design, frame, hit, source(part));
                    if (part != null && request != null) {
                        PacketDistributor.sendToServer(new AssemblyNetworking.Place(menu.containerId, menu.revision(), selectedSlot, request));
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (orbiting) {
            viewport.orbit(dx, dy);
            return true;
        }
        return dragged != null || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && orbiting) {
            orbiting = false;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragged != null) {
            StageCard card = stageAt(x, y);
            if (card != null && card.stage() != dragged.stage() && menu.revision() == dragRevision) {
                edit(dragged.action(), dragged.index(), card.stage());
            }
            dragged = null;
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (x >= stageLeft && x < width - LEFT && y >= VIEW_TOP && y < viewBottom) {
            stageScroll = Mth.clamp(stageScroll - (int) (dy * 24), 0, maxScroll());
            return true;
        }
        if (viewport.contains(x, y)) {
            viewport.zoom(dy);
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Nullable
    private StageCard stageAt(double x, double y) {
        if (x >= stageLeft && x < width - LEFT && y >= VIEW_TOP && y < viewBottom) {
            double offset = y - VIEW_TOP + stageScroll;
            for (StageCard card : cards) {
                if (offset >= card.top() && offset < card.top() + card.height()) {
                    return card;
                }
            }
        }
        return null;
    }

    @Override
    public void onClose() {
        minecraft.player.closeContainer();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void edit(Action action, int index, int stage) {
        PacketDistributor.sendToServer(new AssemblyNetworking.Edit(menu.containerId, menu.revision(), action, index, stage));
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("assembly.blockspaceprogram." + key, args);
    }

    private static String value(double value, int digits) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, digits == 1 ? "%.1f" : "%.2f", value) : "—";
    }

    private record StageAction(Action action, int index, int stage, Component label) {}

    private record StageCard(int stage, int top, StagePerformance performance, List<StageAction> actions) {
        int height() {
            return STATS + actions.size() * CHIP + 2;
        }
    }
}
