package io.github.jxt159753.blockspaceprogram.assembly;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.player_freezing.PlayerFreezeExtension;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundFreezePlayerPacket;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3d;

final class SableHullInteractions {
    private SableHullInteractions() {}

    static boolean inSubLevel(Level level, BlockPos pos) {
        return Sable.HELPER.getContaining(level, pos) != null;
    }

    static void awaitPlot(ServerPlayer player, ServerSubLevel hull, Vec3 position) {
        // Sable releases the client at this anchor once the destination plot can collide.
        var anchor = hull.logicalPose().transformPositionInverse(new Vector3d(position.x, position.y, position.z));
        var id = hull.getUniqueId();
        ((PlayerFreezeExtension) player).sable$freezeTo(id, anchor);
        player.connection.send(new ClientboundCustomPayloadPacket(new ClientboundFreezePlayerPacket(id, anchor)));
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(SableHullInteractions::onBreak);
        NeoForge.EVENT_BUS.addListener(SableHullInteractions::onPlace);
        NeoForge.EVENT_BUS.addListener(SableHullInteractions::onPlayerTick);
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getPlayer() instanceof ServerPlayer player
                && SableShipFlight.hasHullAt(level, event.getPos())) {
            event.setCanceled(true);
            notifyPlayer(player);
        }
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        boolean inHull = event instanceof BlockEvent.EntityMultiPlaceEvent multi
                ? multi.getReplacedBlockSnapshots().stream().anyMatch(snapshot -> SableShipFlight.hasHullAt(level, snapshot.getPos()))
                : SableShipFlight.hasHullAt(level, event.getPos());
        if (inHull) {
            event.setCanceled(true);
            notifyPlayer(player);
        }
    }

    private static void notifyPlayer(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("flight.blockspaceprogram.hull_edit_locked"), true);
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.fallDistance > 0
                && !player.isPassenger() && Sable.HELPER.getTrackingSubLevel(player) instanceof ServerSubLevel hull
                && SableShipFlight.hasHullAt(player.serverLevel(), hull.getPlot().getCenterBlock())) {
            // Sable tracks this player's supporting surface. Its world descent is not a player fall.
            player.resetFallDistance();
        }
    }
}
