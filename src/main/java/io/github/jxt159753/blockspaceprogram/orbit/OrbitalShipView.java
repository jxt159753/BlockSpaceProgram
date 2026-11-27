package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.assembly.OrbitalShipPropulsion;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Map data shared with clients. Ship construction and tank NBT stay on the server. */
public record OrbitalShipView(Orbit orbit, Optional<ManeuverNode> maneuver, Optional<VacuumPropulsion> propulsion) {
    static final StreamCodec<ByteBuf, OrbitalShipView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(Orbit.CODEC), OrbitalShipView::orbit,
            ByteBufCodecs.optional(ManeuverNode.STREAM_CODEC), OrbitalShipView::maneuver,
            ByteBufCodecs.optional(VacuumPropulsion.STREAM_CODEC), OrbitalShipView::propulsion, OrbitalShipView::new);

    static OrbitalShipView of(OrbitingShip ship, HolderLookup.Provider registries) {
        var snapshot = OrbitalShipPropulsion.read(registries, ship.shipData());
        return new OrbitalShipView(ship.orbit(), ship.maneuver(),
                Optional.ofNullable(snapshot).map(OrbitalShipPropulsion::propulsion));
    }
}
