package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.assembly.OrbitalShipPropulsion;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.Propellant;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
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
    private final Propellant propellant;

    private ShipOrbitEvent(Kind kind, UUID shipId, @Nullable UUID ownerId, @Nullable ResourceLocation previousCenter,
            ResourceLocation center, Orbit orbit, Propellant propellant) {
        this.kind = kind;
        this.shipId = shipId;
        this.ownerId = ownerId;
        this.previousCenter = previousCenter;
        this.center = center;
        this.orbit = orbit;
        this.propellant = propellant;
    }

    static ShipOrbitEvent enteredOrbit(UUID shipId, @Nullable UUID ownerId, Orbit orbit, Propellant propellant) {
        return new ShipOrbitEvent(Kind.ENTERED_ORBIT, shipId, ownerId, null, orbit.center(), orbit, propellant);
    }

    static ShipOrbitEvent soiChanged(UUID shipId, @Nullable UUID ownerId, Orbit before, Orbit after, Propellant propellant) {
        return new ShipOrbitEvent(Kind.SOI_CHANGED, shipId, ownerId, before.center(), after.center(), after, propellant);
    }

    static ShipOrbitEvent soiChanged(OrbitingShip ship, HolderLookup.Provider registries, Orbit before, Orbit after) {
        var snapshot = OrbitalShipPropulsion.read(registries, ship.shipData());
        var propellant = snapshot == null ? new Propellant(0, 0)
                : new Propellant(snapshot.propulsion().fuel(), snapshot.propulsion().electric());
        return soiChanged(ship.id(), ship.ownerId(), before, after, propellant);
    }

    static ShipOrbitEvent leftOrbit(UUID shipId, @Nullable UUID ownerId, ResourceLocation destination, Orbit orbit,
            Propellant propellant) {
        return new ShipOrbitEvent(Kind.LEFT_ORBIT, shipId, ownerId, null, destination, orbit, propellant);
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

    /** Remaining fuel at the transition, in kg. */
    public double fuel() {
        return propellant.fuel();
    }

    /** Remaining electrical energy at the transition, in J. */
    public double electric() {
        return propellant.electric();
    }

    public enum Kind {
        ENTERED_ORBIT, SOI_CHANGED, LEFT_ORBIT
    }
}
