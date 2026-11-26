package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AssemblyMenu extends AbstractContainerMenu {
    private final ContainerLevelAccess access;
    private ShipDesign design = ShipDesign.EMPTY;
    private int revision;
    private String message = "";
    private int messageCount;

    public AssemblyMenu(int id, Inventory inventory) {
        this(id, inventory, ContainerLevelAccess.NULL);
    }

    AssemblyMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        super(BlockSpaceProgram.ASSEMBLY_MENU.get(), id);
        this.access = access;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            addSlot(new Slot(inventory, slot, 0, 0) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }
            });
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.getAbilities().mayBuild && stillValid(access, player, BlockSpaceProgram.ASSEMBLY_TABLE.get())
                && access.evaluate((level, pos) -> level.mayInteract(player, pos), true);
    }

    public ShipDesign design() {
        return design;
    }

    public int revision() {
        return revision;
    }

    public String message() {
        return message;
    }

    public int messageCount() {
        return messageCount;
    }

    void receive(AssemblyNetworking.State state) {
        design = state.design();
        revision = state.revision();
        message = state.message();
        messageCount = state.count();
    }

    void place(ServerPlayer player, AssemblyNetworking.Place payload) {
        ItemStack stack = inventorySlot(payload.slot());
        var part = stack.is(BlockSpaceProgram.BAKED_PART.get()) ? stack.get(BlockSpaceProgram.PART_DATA) : null;
        if (part == null) {
            sync(player, "select_part", 0);
            return;
        }
        var result = ShipPlacement.place(design, part, payload.request());
        if (result.error() != null) {
            sync(player, result.error(), 0);
            return;
        }
        design = result.design();
        revision++;
        sync(player, result.skippedMirrors() == 0 ? "" : "mirrors_skipped", result.skippedMirrors());
    }

    void edit(ServerPlayer player, AssemblyNetworking.Edit payload) {
        int index = payload.index();
        int stage = payload.stage();
        ShipDesign before = design;
        String feedback = "";
        switch (payload.action()) {
            case REMOVE -> {
                if (index >= 0 && index < design.nodes().size()) {
                    design = design.remove(index);
                }
            }
            case ADD_STAGE -> {
                if (design.stageCount() < ShipDesign.MAX_STAGES) {
                    design = new ShipDesign(design.nodes(), design.links(), design.stageCount() + 1);
                }
            }
            case REMOVE_STAGE -> {
                if (design.stageCount() > 1 && design.lastStageEmpty()) {
                    design = new ShipDesign(design.nodes(), design.links(), design.stageCount() - 1);
                }
            }
            case MOVE_ENGINE -> {
                if (validStage(stage) && index >= 0 && index < design.nodes().size() && design.nodes().get(index).engineStage() >= 0) {
                    design = design.moveEngine(index, stage);
                }
            }
            case MOVE_SEPARATOR -> {
                if (validStage(stage) && index >= 0 && index < design.links().size() && design.links().get(index).separable()) {
                    design = design.moveSeparator(index, stage);
                }
            }
            case SAVE -> {
                ItemStack stack = inventorySlot(index);
                if (stack.is(BlockSpaceProgram.SHIP_BLUEPRINT.get()) && !stack.has(BlockSpaceProgram.SHIP_DESIGN) && design.isValid()) {
                    stack.set(BlockSpaceProgram.SHIP_DESIGN, design);
                    player.getInventory().setChanged();
                    feedback = "saved";
                } else {
                    feedback = "save_requires_blank";
                }
            }
            case LOAD -> {
                ItemStack stack = inventorySlot(index);
                var blueprint = stack.is(BlockSpaceProgram.SHIP_BLUEPRINT.get()) ? stack.get(BlockSpaceProgram.SHIP_DESIGN) : null;
                if (blueprint != null && blueprint.isValid()) {
                    design = blueprint;
                    feedback = "loaded";
                } else {
                    feedback = "load_requires_blueprint";
                }
            }
        }
        if (before != design) {
            revision++;
        }
        sync(player, feedback, 0);
    }

    private boolean validStage(int stage) {
        return stage >= 0 && stage < design.stageCount();
    }

    private ItemStack inventorySlot(int index) {
        return index >= 0 && index < slots.size() ? getSlot(index).getItem() : ItemStack.EMPTY;
    }

    private void sync(ServerPlayer player, String feedback, int count) {
        PacketDistributor.sendToPlayer(player, new AssemblyNetworking.State(containerId, revision, design, feedback, count));
    }
}
