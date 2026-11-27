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
    private final ResourceLocation center;
    private final Orbit orbit;

    private ShipOrbitEvent(Kind kind, UUID shipId, @Nullable UUID ownerId, @Nullable ResourceLocation previousCenter,
            ResourceLocation center, Orbit orbit) {
        this.kind = kind;
        this.shipId = shipId;
        this.ownerId = ownerId;
        this.previousCenter = previousCenter;
        this.center = center;
        this.orbit = orbit;
    }

    static ShipOrbitEvent enteredOrbit(UUID shipId, @Nullable UUID ownerId, Orbit orbit) {
        return new ShipOrbitEvent(Kind.ENTERED_ORBIT, shipId, ownerId, null, orbit.center(), orbit);
    }

    static ShipOrbitEvent soiChanged(UUID shipId, @Nullable UUID ownerId, Orbit before, Orbit after) {
        return new ShipOrbitEvent(Kind.SOI_CHANGED, shipId, ownerId, before.center(), after.center(), after);
    }

    static ShipOrbitEvent leftOrbit(UUID shipId, @Nullable UUID ownerId, ResourceLocation destination, Orbit orbit) {
        return new ShipOrbitEvent(Kind.LEFT_ORBIT, shipId, ownerId, null, destination, orbit);
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

    /** The new SOI, or the destination dimension for an exit. */
    public ResourceLocation center() {
        return center;
    }

    /** The transition conic, or the last conic on exit. Local SOI changes and exits report the shared reference. */
    public Orbit orbit() {
        return orbit;
    }

    public enum Kind {
        ENTERED_ORBIT, SOI_CHANGED, LEFT_ORBIT
    }
}
