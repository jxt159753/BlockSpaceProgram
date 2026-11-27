package io.github.jxt159753.blockspaceprogram.facility;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
public interface FacilityOwnerResolver {
    Owner resolve(ServerPlayer player);

    static Owner personal(ServerPlayer player) {
        return new Owner(player.getUUID(), Component.translatable("facility.blockspaceprogram.owner_player", player.getName()));
    }

    record Owner(UUID id, Component name) {}
}
