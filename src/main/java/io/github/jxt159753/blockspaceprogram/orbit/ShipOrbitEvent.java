package io.github.jxt159753.blockspaceprogram.orbit;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/** Posted on the game event bus after a ship's orbital transition on the server thread. */
public final class ShipOrbitEvent extends Event {
    private final Kind kind;
    private final UUID shipId;
    @Nullable private final UUID ownerId;
    @Nullable private final ResourceLocation previousCenter;
    private final Orbit orbit;

    private ShipOrbitEvent(Kind kind, UUID shipId, @Nullable UUID ownerId, @Nullable ResourceLocation previousCenter,
            Orbit orbit) {
        this.kind = kind;
        this.shipId = shipId;
        this.ownerId = ownerId;
        this.previousCenter = previousCenter;
        this.orbit = orbit;
    }

    static ShipOrbitEvent enteredOrbit(UUID shipId, @Nullable UUID ownerId, Orbit orbit) {
        return new ShipOrbitEvent(Kind.ENTERED_ORBIT, shipId, ownerId, null, orbit);
    }

    static ShipOrbitEvent soiChanged(UUID shipId, @Nullable UUID ownerId, Orbit before, Orbit after) {
        return new ShipOrbitEvent(Kind.SOI_CHANGED, shipId, ownerId, before.center(), after);
    }

    public Kind kind() {
        return kind;
    }

    public UUID shipId() {
        return shipId;
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    @Nullable
    public ResourceLocation previousCenter() {
        return previousCenter;
    }

    /** The new conic. In the local physics region, a SOI change reports the shared reference orbit. */
    public Orbit orbit() {
        return orbit;
    }

    public enum Kind {
        ENTERED_ORBIT, SOI_CHANGED
    }
}
