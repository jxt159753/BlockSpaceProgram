package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.DockingCapacityDesigns;
import io.github.jxt159753.blockspaceprogram.assembly.DockingCapacityDesigns.Shape;
import io.github.jxt159753.blockspaceprogram.assembly.DockingCapacityTiming;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight.Motion;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Quaterniond;
import org.slf4j.Logger;

final class DockingCapacityOperations {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double OPEN_GAP = 4;
    private static final double CAPTURE_GAP = 0.125;
    private static DockingCapacityOperations active;

    private final ServerLevel space;
    private final int parts;
    private final UUID primary;
    private final UUID incoming;
    private CompoundTag merged;

    private DockingCapacityOperations(ServerPlayer player, int parts) {
        if (!ModList.get().isLoaded("sable")) {
            throw new IllegalStateException("This trial requires Sable");
        }
        this.parts = parts;
        var pair = DockingCapacityDesigns.pair(new Shape(parts, 27, 1_024));
        space = player.server.getLevel(BlockSpaceProgram.ORBITAL_SPACE);
        var origin = OrbitalRegion.ORIGIN;
        var first = DockingCapacityDesigns.spawn(space, pair.primary(), origin, parts + "p_ops_primary", false);
        var peer = DockingCapacityDesigns.spawn(space, pair.incoming(), origin.add(pair.offset() + OPEN_GAP, 0, 0),
                parts + "p_ops_incoming", false);
        primary = first.getUUID();
        incoming = peer.getUUID();
        DockingCapacityDesigns.damageLastPart(peer);
        var camera = origin.add(pair.offset(), 28, -45);
        SableShipFlight.movePlayer(player, space, camera, Vec3.ZERO, 0, 0);
    }

    static LiteralArgumentBuilder<CommandSourceStack> commands() {
        return Commands.literal("ops")
                .then(Commands.literal("spawn").then(Commands.argument("parts", IntegerArgumentType.integer(72, 288))
                        .executes(context -> {
                            active = new DockingCapacityOperations(context.getSource().getPlayerOrException(),
                                    IntegerArgumentType.getInteger(context, "parts"));
                            return 1;
                        })))
                .then(Commands.literal("dock").executes(context -> active.dock()))
                .then(Commands.literal("undock").executes(context -> active.undock()))
                .then(Commands.literal("orbit").executes(context -> active.enterOrbit(context.getSource().getPlayerOrException())))
                .then(Commands.literal("persist").executes(context -> active.persist(context.getSource().getPlayerOrException())))
                .then(Commands.literal("save_max").executes(context -> saveMax(context.getSource().getPlayerOrException())))
                .then(Commands.literal("read_max").executes(context -> readMax(context.getSource().getLevel())));
    }

    private int dock() {
        var peer = ship(space, incoming);
        var flight = peer.activeFlight();
        var before = flight.motion();
        flight.setMotion(new Motion(before.center().add(CAPTURE_GAP - OPEN_GAP, 0, 0), Vec3.ZERO,
                before.orientation(), Vec3.ZERO));
        // Capture runs in the regular level tick listener.
        return 1;
    }

    private int undock() {
        var station = ship(space, primary);
        if (station.design().partCount() != parts || DockingCapacityDesigns.originCount(station) != 2
                || DockingCapacityDesigns.highestDamageIndex(station) < Long.SIZE || space.getEntity(incoming) != null) {
            throw new IllegalStateException("Docking did not retain both stage blocks and the incoming damage bit");
        }
        merged = station.orbitSnapshot();
        DockingCapacityTiming.nextTick(() -> {
            if (!DockingCapacityDesigns.release(station)) {
                throw new IllegalStateException("The docking link did not release");
            }
        });
        return 1;
    }

    private int enterOrbit(ServerPlayer player) {
        var first = ship(space, primary);
        var restoredPeer = ship(space, incoming);
        if (first.design().partCount() + restoredPeer.design().partCount() != parts
                || first.design().stageCount() != 2 || restoredPeer.design().stageCount() != 2
                || restoredPeer.damagedPartCount() != 1) {
            throw new IllegalStateException("Undocking did not restore both ship identities, stages, and damage");
        }
        first.activeFlight().removeForOrbit();
        restoredPeer.activeFlight().removeForOrbit();
        var data = OrbitalSavedData.get(space.getServer());
        var orbit = trialOrbit(data, 0);
        data.put(new OrbitingShip(primary, orbit, OrbitalRegion.toInertialRotation(), Vec3.ZERO, merged));
        var observer = trialOrbit(data, -0.0007);
        var saved = new CompoundTag();
        saved.put("orbit", Orbit.CODEC.encodeStart(NbtOps.INSTANCE, observer).getOrThrow());
        saved.putDouble("seconds", data.orbitSeconds());
        player.getPersistentData().put(OrbitalRegion.PLAYER_RETURN, saved);
        // Use the same entry and subsequent contact tick that serve returning players.
        OrbitalRegion.get(space.getServer()).onLogin(player, data);
        return 1;
    }

    private int persist(ServerPlayer player) {
        var station = ship(space, primary);
        if (station.design().partCount() != parts || DockingCapacityDesigns.originCount(station) != 2
                || DockingCapacityDesigns.highestDamageIndex(station) < Long.SIZE) {
            throw new IllegalStateException("The orbital restore did not retain the merged ship state");
        }
        station.activeFlight().removeForOrbit();
        var ground = player.server.overworld();
        // Widely spaced positions let each saved Sable plot load in a separate tick on restart.
        var position = new Vec3((parts - 72) * 4, -20, 0);
        SableShipFlight.movePlayer(player, ground, position.add(0, 5, -38), Vec3.ZERO, 0, 0);
        var physical = AssembledShipEntity.restoreFromOrbit(ground, primary, merged,
                new Motion(position, Vec3.ZERO, new Quaterniond(), Vec3.ZERO), false);
        if (physical == null) {
            throw new IllegalStateException("Could not place the merged ship in the restart save");
        }
        LOGGER.info("CAPACITY PERSIST parts={} x={} uuid={}", parts, position.x, physical.getUUID());
        return 1;
    }

    private static int saveMax(ServerPlayer player) {
        var data = OrbitalSavedData.get(player.server);
        var ship = SableShipFlight.activeShips(player.serverLevel()).stream()
                .filter(candidate -> candidate.getName().getString().endsWith("_watch"))
                .findFirst().orElseThrow();
        var flight = ship.activeFlight();
        ShipOrbitHandoff.storeFlight(data, ship, flight.motion(), trialOrbit(data, 0), OrbitalRegion.toInertialRotation());
        LOGGER.info("CAPACITY SAVED_MAX uuid={}", ship.getUUID());
        SableShipFlight.movePlayer(player, player.server.overworld(), new Vec3(0.5, -56, 0.5), Vec3.ZERO, 0, 0);
        return 1;
    }

    private static int readMax(ServerLevel level) {
        int size = 0;
        for (var snapshot : OrbitalSavedData.get(level.getServer()).ships()) {
            var saved = ItemStack.parseOptional(level.registryAccess(), snapshot.shipData().getCompound("Blueprint"));
            var design = saved.get(BlockSpaceProgram.FLIGHT_LAYOUT);
            if (design != null && design.partCount() == 72 && design.nodes().size() == 99) {
                size = design.nodes().stream().map(ShipDesign.Node::hull)
                        .filter(hull -> hull != null).mapToInt(hull -> hull.size()).sum();
            }
        }
        if (size != 27 * 8_192) {
            throw new IllegalStateException("The maximum NBT payload sample is missing or incomplete");
        }
        LOGGER.info("CAPACITY READ_MAX parts=72 hulls=27 blocks={} sable={}", size, ModList.get().isLoaded("sable"));
        return 1;
    }

    private static Orbit trialOrbit(OrbitalSavedData data, double phase) {
        var body = data.system().body(Level.OVERWORLD.location());
        return new Orbit(body.id(), new OrbitElements(body.radius() + 10_000, 0, 0, 0, 0, phase, data.orbitSeconds()));
    }

    private static AssembledShipEntity ship(ServerLevel level, UUID id) {
        if (level.getEntity(id) instanceof AssembledShipEntity ship && ship.activeFlight() != null) {
            return ship;
        }
        throw new IllegalStateException("Missing live trial ship " + id);
    }
}
