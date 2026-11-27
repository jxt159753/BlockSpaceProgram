package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/** Posted on the game event bus after a physical flight transition on the server thread. */
public final class ShipFlightEvent extends Event {
    private final AssembledShipEntity ship;
    private final Kind kind;
    private final int stage;
    private final List<UUID> separatedShipIds;

    ShipFlightEvent(AssembledShipEntity ship, Kind kind, int stage) {
        this(ship, kind, stage, List.of());
    }

    ShipFlightEvent(AssembledShipEntity ship, Kind kind, int stage, List<UUID> separatedShipIds) {
        this.ship = ship;
        this.kind = kind;
        this.stage = stage;
        this.separatedShipIds = List.copyOf(separatedShipIds);
    }

    public AssembledShipEntity ship() {
        return ship;
    }

    public UUID shipId() {
        return ship.getUUID();
    }

    @Nullable
    public UUID ownerId() {
        return ship.owner();
    }

    public ResourceLocation dimensionId() {
        return ship.level().dimension().location();
    }

    public Kind kind() {
        return kind;
    }

    /** The retained ship's zero-based stage after the transition, or -1 before the first stage. */
    public int stage() {
        return stage;
    }

    /** IDs of the other fragments from a stage split. Empty for a stage change that left the ship joined. */
    public List<UUID> separatedShipIds() {
        return separatedShipIds;
    }

    public enum Kind {
        FUEL_DEPLETED, LANDED, STAGE_CHANGED
    }
}
