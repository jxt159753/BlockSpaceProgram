package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.facility.Facility;
import io.github.jxt159753.blockspaceprogram.facility.FacilityLimit;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

final class ShipFacilityLimits {
    private ShipFacilityLimits() {}

    static FacilityLimit parts(ServerPlayer player) {
        return BlockSpaceProgram.facilities().limit(player, Facility.ASSEMBLY_TABLE);
    }

    static FacilityLimit mass(ServerPlayer player) {
        return BlockSpaceProgram.facilities().limit(player, Facility.LAUNCH_PAD);
    }

    @Nullable
    static Component checkParts(ServerPlayer player, ShipDesign design) {
        return Facility.ASSEMBLY_TABLE.rejection(parts(player), design.partCount());
    }

    @Nullable
    static Component checkLaunch(ServerPlayer player, ShipDesign design) {
        var rejection = checkParts(player, design);
        if (rejection != null) {
            return rejection;
        }
        var limit = mass(player);
        return limit.maximum() == 0 ? null : Facility.LAUNCH_PAD.rejection(limit, design.fullMass());
    }
}
