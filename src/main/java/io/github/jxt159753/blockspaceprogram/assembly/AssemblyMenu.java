package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.PlacedPartEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

public final class AssemblyMenu extends AbstractContainerMenu {
    private final ContainerLevelAccess access;
    private final Inventory inventory;
    @Nullable
    private final PlacedPartEntity worldPod;
    @Nullable
    private WorldAssembly.Inspection worldView;
    private ShipDesign design = ShipDesign.EMPTY;
    private int revision;
    private String message = "";
    private int messageCount;

    public AssemblyMenu(int id, Inventory inventory) {
        this(id, inventory, ContainerLevelAccess.NULL);
    }

    AssemblyMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        this(id, inventory, BlockSpaceProgram.ASSEMBLY_MENU.get(), access, null);
    }

    AssemblyMenu(int id, Inventory inventory, PlacedPartEntity pod) {
        this(id, inventory, BlockSpaceProgram.WORLD_ASSEMBLY_MENU.get(), ContainerLevelAccess.NULL, pod);
    }

    public static AssemblyMenu worldClient(int id, Inventory inventory) {
        return new AssemblyMenu(id, inventory, BlockSpaceProgram.WORLD_ASSEMBLY_MENU.get(), ContainerLevelAccess.NULL, null);
    }

    private AssemblyMenu(int id, Inventory inventory, MenuType<AssemblyMenu> type, ContainerLevelAccess access,
            @Nullable PlacedPartEntity worldPod) {
        super(type, id);
        this.access = access;
        this.inventory = inventory;
        this.worldPod = worldPod;
        if (isWorldAssembly()) {
            return;
        }
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
        if (isWorldAssembly()) {
            return worldPod == null || WorldAssembly.canUse(player, worldPod);
        }
        return player.getAbilities().mayBuild && stillValid(access, player, BlockSpaceProgram.ASSEMBLY_TABLE.get())
                && access.evaluate((level, pos) -> level.mayInteract(player, pos), true);
    }

    public boolean isWorldAssembly() {
        return getType() == BlockSpaceProgram.WORLD_ASSEMBLY_MENU.get();
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (worldPod != null && inventory.player instanceof ServerPlayer player && stillValid(player)) {
            refreshWorld(player);
        }
    }

    boolean accepts(ServerPlayer player, int expectedRevision) {
        if (!stillValid(player)) {
            return false;
        }
        if (worldPod != null) {
            refreshWorld(player);
        }
        return expectedRevision == revision;
    }

    private void refreshWorld(ServerPlayer player) {
        var level = player.serverLevel();
        var current = WorldAssembly.get(level).inspect(level, worldPod);
        if (!current.equals(worldView)) {
            worldView = current;
            design = current.snapshot() == null ? ShipDesign.EMPTY : current.snapshot().design();
            revision++;
            sync(player, current.error(), 0);
        }
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
        if (isWorldAssembly()) {
            return;
        }
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
        var snapshot = worldView == null ? null : worldView.snapshot();
        if (worldPod != null) {
            switch (payload.action()) {
                case ASSEMBLE -> {
                    String error = WorldAssembly.assemble(player, worldPod);
                    if (error == null) {
                        player.closeContainer();
                    } else {
                        sync(player, error, 0);
                    }
                    return;
                }
                case REMOVE, SAVE, LOAD -> {
                    return;
                }
                default -> {
                    if (snapshot == null || !snapshot.canEdit(player)) {
                        sync(player, snapshot == null ? worldView.error() : "world_permission", 0);
                        return;
                    }
                }
            }
        }
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
            case ASSEMBLE -> {
                return;
            }
        }
        if (before != design) {
            if (snapshot != null) {
                var level = player.serverLevel();
                WorldAssembly.get(level).saveStages(level, snapshot, design);
                refreshWorld(player);
                return;
            }
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
