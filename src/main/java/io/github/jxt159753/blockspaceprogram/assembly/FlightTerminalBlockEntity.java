package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class FlightTerminalBlockEntity extends BlockEntity {
    @Nullable
    private UUID owner;
    @Nullable
    private UUID shipId;
    private int bindingRevision;

    public FlightTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(BlockSpaceProgram.FLIGHT_TERMINAL_ENTITY.get(), pos, state);
    }

    void claim(Player player) {
        if (owner == null) {
            owner = player.getUUID();
            setChanged();
        }
    }

    boolean canConfigure(Player player) {
        return isAvailable() && player.level() == level && player.isAlive() && !player.isSpectator()
                && player.getAbilities().mayBuild && player.canInteractWithBlock(worldPosition, 0)
                && level.mayInteract(player, worldPosition) && (owner == null || owner.equals(player.getUUID()));
    }

    boolean bind(Player player, @Nullable UUID target) {
        if (!canConfigure(player) || (target != null && resolve(target) == null)) {
            return false;
        }
        if (!Objects.equals(shipId, target)) {
            shipId = target;
            bindingRevision++;
            setChanged();
        }
        return true;
    }

    @Nullable
    public UUID shipId() {
        return shipId;
    }

    @Nullable
    public UUID ownerId() {
        return owner;
    }

    public int bindingRevision() {
        return bindingRevision;
    }

    public boolean isAvailable() {
        return level instanceof ServerLevel && !isRemoved() && level.isLoaded(worldPosition);
    }

    public boolean isBoundTo(AssembledShipEntity ship) {
        return level instanceof ServerLevel server && server.getServer() == ship.getServer()
                && isBoundTo(ship.getUUID(), ship.owner());
    }

    public boolean isBoundTo(UUID id, @Nullable UUID shipOwner) {
        return isAvailable() && id.equals(shipId) && owner != null && owner.equals(shipOwner);
    }

    @Nullable
    public ShipFlightControl flightControl() {
        var ship = shipId == null ? null : resolve(shipId);
        return ship == null ? null : ship.flightControl();
    }

    @Nullable
    private AssembledShipEntity resolve(UUID target) {
        return owner != null && isAvailable() && level instanceof ServerLevel server
                ? AssembledShipEntity.findOwnedFlight(server.getServer(), target, owner) : null;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        shipId = tag.hasUUID("Ship") ? tag.getUUID("Ship") : null;
        bindingRevision++;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (shipId != null) {
            tag.putUUID("Ship", shipId);
        }
    }
}
