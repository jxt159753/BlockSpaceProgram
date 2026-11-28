package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.assembly.OrbitalShipPropulsion;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Map data shared with clients. Ship construction and tank NBT stay on the server. */
public record OrbitalShipView(Orbit orbit, Optional<ManeuverNode> maneuver, Optional<VacuumPropulsion> propulsion) {
    // Legacy payloads used a plain group count and one scalar thrust per group.
    private static final int VECTOR_GROUP_OFFSET = ShipDesign.MAX_PARTS + 1;
    private static final StreamCodec<ByteBuf, VacuumPropulsion> PROPULSION_CODEC = StreamCodec.of((buffer, propulsion) -> {
        int count = propulsion.groups().size();
        if (count > ShipDesign.MAX_PARTS) {
            throw new EncoderException("Too many orbital fuel groups: " + count);
        }
        buffer.writeDouble(propulsion.wetMass());
        buffer.writeDouble(propulsion.fuel());
        ByteBufCodecs.VAR_INT.encode(buffer, count + VECTOR_GROUP_OFFSET);
        for (var group : propulsion.groups()) {
            buffer.writeDouble(group.fuel());
            buffer.writeDouble(group.thrustX());
            buffer.writeDouble(group.thrustY());
            buffer.writeDouble(group.thrustZ());
            buffer.writeDouble(group.flow());
        }
    }, buffer -> {
        double wetMass = buffer.readDouble();
        double fuel = buffer.readDouble();
        int count = ByteBufCodecs.VAR_INT.decode(buffer);
        boolean vectors = count >= VECTOR_GROUP_OFFSET;
        if (vectors) {
            count -= VECTOR_GROUP_OFFSET;
        }
        if (count < 0 || count > ShipDesign.MAX_PARTS) {
            throw new DecoderException("Invalid orbital fuel group count: " + count);
        }
        var groups = new ArrayList<VacuumPropulsion.Group>(count);
        for (int i = 0; i < count; i++) {
            double groupFuel = buffer.readDouble();
            double thrustX = buffer.readDouble();
            double thrustY = vectors ? buffer.readDouble() : 0;
            double thrustZ = vectors ? buffer.readDouble() : 0;
            groups.add(new VacuumPropulsion.Group(groupFuel, thrustX, thrustY, thrustZ, buffer.readDouble()));
        }
        return new VacuumPropulsion(wetMass, fuel, groups);
    });

    static final StreamCodec<ByteBuf, OrbitalShipView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(Orbit.CODEC), OrbitalShipView::orbit,
            ByteBufCodecs.optional(ManeuverNode.STREAM_CODEC), OrbitalShipView::maneuver,
            ByteBufCodecs.optional(PROPULSION_CODEC), OrbitalShipView::propulsion, OrbitalShipView::new);

    static OrbitalShipView of(OrbitingShip ship, HolderLookup.Provider registries) {
        var snapshot = OrbitalShipPropulsion.read(registries, ship.shipData());
        return new OrbitalShipView(ship.orbit(), ship.maneuver(),
                Optional.ofNullable(snapshot).map(OrbitalShipPropulsion::propulsion));
    }
}
