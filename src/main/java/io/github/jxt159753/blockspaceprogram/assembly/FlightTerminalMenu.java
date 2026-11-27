package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class FlightTerminalMenu extends AbstractContainerMenu {
    private static final int MAX_TARGETS = 1024; // 24 bytes per target in the menu-open payload.
    private final List<Target> targets;
    private final DataSlot selection = DataSlot.standalone();
    @Nullable
    private final FlightTerminalBlockEntity terminal;

    public FlightTerminalMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(id, data.readList(buf -> new Target(buf.readUUID(), buf.readBlockPos())), null);
    }

    private FlightTerminalMenu(int id, List<Target> targets, @Nullable FlightTerminalBlockEntity terminal) {
        super(BlockSpaceProgram.FLIGHT_TERMINAL_MENU.get(), id);
        this.targets = targets;
        this.terminal = terminal;
        addDataSlot(selection);
        updateSelection();
    }

    static void open(ServerPlayer player, FlightTerminalBlockEntity terminal, Component title) {
        var position = terminal.getBlockPos();
        var targets = AssembledShipEntity.ownedFlights(player.serverLevel(), player.getUUID()).stream()
                .map(ship -> new Target(ship.getUUID(), BlockPos.containing(ship.activeFlight().motion().center())))
                .sorted(Comparator.comparingDouble((Target target) -> target.position().distSqr(position)).thenComparing(Target::shipId))
                .limit(MAX_TARGETS)
                .toList();
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new FlightTerminalMenu(id, targets, terminal), title),
                buf -> buf.writeCollection(targets, (out, target) -> {
                    out.writeUUID(target.shipId());
                    out.writeBlockPos(target.position());
                }));
    }

    public List<Target> targets() {
        return targets;
    }

    /** Zero means unbound, -1 means the bound ship is absent, and a positive value selects a row. */
    public int selection() {
        return selection.get();
    }

    private void updateSelection() {
        if (terminal == null) {
            return;
        }
        UUID shipId = terminal.shipId();
        if (shipId == null) {
            selection.set(0);
            return;
        }
        for (int index = 0; index < targets.size(); index++) {
            if (shipId.equals(targets.get(index).shipId())) {
                selection.set(index + 1);
                return;
            }
        }
        selection.set(-1);
    }

    @Override
    public void broadcastChanges() {
        updateSelection();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (terminal == null || id < 0 || id > targets.size()) {
            return false;
        }
        if (!terminal.bind(player, id == 0 ? null : targets.get(id - 1).shipId())) {
            player.displayClientMessage(Component.translatable("terminal.blockspaceprogram.unavailable"), true);
            return false;
        }
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return terminal == null || terminal.canConfigure(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    public record Target(UUID shipId, BlockPos position) {
        public String shortId() {
            return shipId.toString().substring(0, 8);
        }
    }
}
