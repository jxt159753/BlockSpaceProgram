package io.github.jxt159753.blockspaceprogram.compat.ftbteams;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import io.github.jxt159753.blockspaceprogram.facility.FacilityOwnerResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class FtbTeamsFacilityOwnerResolver implements FacilityOwnerResolver {
    @Override
    public Owner resolve(ServerPlayer player) {
        var api = FTBTeamsAPI.api();
        if (api.isManagerLoaded()) {
            var team = api.getManager().getTeamForPlayer(player).orElse(null);
            if (team != null) {
                Component name = team.isPartyTeam()
                        ? Component.translatable("facility.blockspaceprogram.owner_team", team.getName())
                        : Component.translatable("facility.blockspaceprogram.owner_player", player.getName());
                return new Owner(team.getId(), name);
            }
        }
        return FacilityOwnerResolver.personal(player);
    }
}
