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
    // Keep the scalar and vector marker ranges used by the original 36-group format.
    private static final int VECTOR_GROUP_OFFSET = ShipDesign.MAX_PARTS + 1;
    private static final int ELECTRIC_GROUP_OFFSET = 2 * VECTOR_GROUP_OFFSET;
    private static final StreamCodec<ByteBuf, VacuumPropulsion.Drive> DRIVE_CODEC = StreamCodec.of((buffer, drive) -> {
        buffer.writeDouble(drive.thrustX());
        buffer.writeDouble(drive.thrustY());
        buffer.writeDouble(drive.thrustZ());
        buffer.writeDouble(drive.flow());
    }, buffer -> new VacuumPropulsion.Drive(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
            buffer.readDouble()));
    private static final StreamCodec<ByteBuf, VacuumPropulsion> PROPULSION_CODEC = StreamCodec.of((buffer, propulsion) -> {
        int count = propulsion.groups().size();
        if (count > ShipDesign.MAX_MERGED_PARTS) {
            throw new EncoderException("Too many orbital fuel groups: " + count);
        }
        buffer.writeDouble(propulsion.wetMass());
        buffer.writeDouble(propulsion.fuel());
        ByteBufCodecs.VAR_INT.encode(buffer, count + ELECTRIC_GROUP_OFFSET);
        buffer.writeDouble(propulsion.electric());
        for (var group : propulsion.groups()) {
            ByteBufCodecs.VAR_INT.encode(buffer, group.index());
            buffer.writeDouble(group.fuel());
            DRIVE_CODEC.encode(buffer, group.chemical());
            DRIVE_CODEC.encode(buffer, group.electric());
            buffer.writeDouble(group.electricPower());
        }
    }, buffer -> {
        double wetMass = buffer.readDouble();
        double fuel = buffer.readDouble();
        int header = ByteBufCodecs.VAR_INT.decode(buffer);
        if (header < 0 || header > ELECTRIC_GROUP_OFFSET + ShipDesign.MAX_MERGED_PARTS) {
            throw new DecoderException("Invalid orbital fuel group header: " + header);
        }
        boolean vectors = header >= VECTOR_GROUP_OFFSET;
        boolean electrical = header >= ELECTRIC_GROUP_OFFSET;
        int count = electrical ? header - ELECTRIC_GROUP_OFFSET : header % VECTOR_GROUP_OFFSET;
        double electric = electrical ? buffer.readDouble() : 0;
        var groups = new ArrayList<VacuumPropulsion.Group>(count);
        for (int i = 0; i < count; i++) {
            int index = electrical ? ByteBufCodecs.VAR_INT.decode(buffer) : i;
            double groupFuel = buffer.readDouble();
            var chemical = vectors ? DRIVE_CODEC.decode(buffer)
                    : new VacuumPropulsion.Drive(buffer.readDouble(), 0, 0, buffer.readDouble());
            var electricDrive = electrical ? DRIVE_CODEC.decode(buffer) : new VacuumPropulsion.Drive(0, 0, 0, 0);
            double power = electrical ? buffer.readDouble() : 0;
            groups.add(new VacuumPropulsion.Group(index, groupFuel, chemical, electricDrive, power));
        }
        return new VacuumPropulsion(wetMass, fuel, electric, groups);
    });

    static final StreamCodec<ByteBuf, OrbitalShipView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(Orbit.CODEC), OrbitalShipView::orbit,
            ByteBufCodecs.optional(ManeuverNode.STREAM_CODEC), OrbitalShipView::maneuver,
            ByteBufCodecs.optional(PROPULSION_CODEC), OrbitalShipView::propulsion, OrbitalShipView::new);

    static OrbitalShipView of(OrbitingShip ship, HolderLookup.Provider registries) {
        var snapshot = OrbitalShipPropulsion.read(registries, ship.shipData());
        var maneuver = ship.maneuver().map(node -> ship.maneuverProgress().map(progress -> {
            double fraction = 1 - progress.deltaV() / node.deltaV().length();
            return new ManeuverNode(progress.startedAt(), node.deltaV().scale(fraction));
        }).orElse(node));
        return new OrbitalShipView(ship.orbit(), maneuver, Optional.ofNullable(snapshot).map(OrbitalShipPropulsion::propulsion));
    }
}
