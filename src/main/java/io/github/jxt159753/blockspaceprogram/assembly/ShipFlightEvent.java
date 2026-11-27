package io.github.jxt159753.blockspaceprogram.assembly;

import net.neoforged.bus.api.Event;

/** Posted on the server thread after a near-surface flight transition. Stage uses the Java zero-based index. */
public final class ShipFlightEvent extends Event {
    private final AssembledShipEntity ship;
    private final Kind kind;
    private final int stage;

    ShipFlightEvent(AssembledShipEntity ship, Kind kind, int stage) {
        this.ship = ship;
        this.kind = kind;
        this.stage = stage;
    }

    public AssembledShipEntity ship() {
        return ship;
    }

    public Kind kind() {
        return kind;
    }

    public int stage() {
        return stage;
    }

    public enum Kind {
        FUEL_DEPLETED, LANDED, STAGE_CHANGED
    }
}
