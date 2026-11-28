package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Side;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight.Motion;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class ReentryTrials {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MODE = System.getProperty("bsp.reentryHeating.trial", "");
    private static final double SEA_LEVEL = 63;
    private static final int MAX_TICKS = 9_000;
    private static final int BENCH_WARMUP = 60;
    private static final int BENCH_SAMPLES = 160;
    private static final Vec3 VISUAL_AIR_VELOCITY = new Vec3(815, -25, 0);
    private static int readyTicks;
    private static int ticks;
    private static long tickStart;
    private static List<PathTrial> paths;
    private static List<AssembledShipEntity> benchmarkShips;
    private static final long[] EMPTY = new long[BENCH_SAMPLES];
    private static final long[] LOADED = new long[BENCH_SAMPLES];
    private static AssembledShipEntity visualShip;
    private static boolean finished;

    private ReentryTrials() {}

    @SubscribeEvent
    public static void beforeTick(ServerTickEvent.Pre event) {
        if (MODE.equals("benchmark")) {
            tickStart = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void afterTick(ServerTickEvent.Post event) {
        long elapsed = MODE.equals("benchmark") ? System.nanoTime() - tickStart : 0;
        var server = event.getServer();
        if (MODE.isEmpty() || finished || !server.isDedicatedServer() || !server.isReady()) {
            return;
        }
        if (ticks == 0 && ((MODE.equals("visual") && server.getPlayerCount() == 0) || ++readyTicks < 60)) {
            return;
        }
        var level = server.overworld();
        try {
            if (ticks == 0) {
                require(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) == SEA_LEVEL,
                        "Fixture world needs flat ground at Y=63");
                require(ModList.get().isLoaded("sable") != MODE.equals("no-sable"), "Unexpected Sable installation");
                LOGGER.info("REENTRY START trial={} heat={}", MODE, ReentryHeating.ENABLED);
                if (!MODE.equals("visual") && !MODE.equals("no-sable")) {
                    server.tickRateManager().requestGameToSprint(MAX_TICKS);
                }
            }
            boolean done = switch (MODE) {
                case "trajectories" -> trajectories(level);
                case "benchmark" -> benchmark(level, elapsed);
                case "visual" -> visual(level);
                case "no-sable" -> staticShip(level);
                default -> throw new IllegalArgumentException("Unknown reentry trial: " + MODE);
            };
            ticks++;
            if (done) {
                finished = true;
                LOGGER.info("REENTRY DONE trial={} ticks={}", MODE, ticks);
                server.halt(false);
            } else if (ticks >= MAX_TICKS) {
                throw new IllegalStateException("Reentry trial timed out");
            }
        } catch (RuntimeException error) {
            finished = true;
            LOGGER.error("REENTRY FAIL trial={}", MODE, error);
            server.halt(false);
        }
    }

    private static boolean trajectories(ServerLevel level) {
        if (ticks == 0) {
            paths = List.of(new PathTrial(level, "leo-iron", false, false, true, 0),
                    new PathTrial(level, "leo-shield", true, false, true, 1),
                    new PathTrial(level, "leo-wood", false, true, true, 2),
                    new PathTrial(level, "moon-iron", false, false, false, 0),
                    new PathTrial(level, "moon-shield", true, false, false, 1));
        }
        boolean done = true;
        for (var path : paths) {
            done &= path.tick(level);
        }
        return done;
    }

    private static boolean benchmark(ServerLevel level, long elapsed) {
        int load = BENCH_WARMUP + BENCH_SAMPLES;
        if (ticks >= BENCH_WARMUP && ticks < load) {
            EMPTY[ticks - BENCH_WARMUP] = elapsed;
        }
        if (ticks == load) {
            var design = AeroTrialDesigns.benchmark();
            require(design.nodes().size() == 63, "Expected 63 nodes per benchmark ship");
            var ships = new ArrayList<AssembledShipEntity>();
            for (int i = 0; i < 10; i++) {
                var origin = new Vec3(32 * (i % 5), 100, 32 * (i / 5));
                level.getChunkAt(BlockPos.containing(origin));
                var ship = Objects.requireNonNull(AssembledShipEntity.spawnBenchmark(level, design, origin));
                var center = ship.dimensionCenter();
                var start = new Vec3(center.x, 4_120, center.z);
                flight(ship).setMotion(new Motion(start, new Vec3(0, -280, 0),
                        new Quaterniond().rotationX(Math.toRadians(5)), Vec3.ZERO));
                ships.add(ship);
            }
            benchmarkShips = ships;
        }
        int sample = ticks - load - BENCH_WARMUP;
        if (sample >= 0 && sample < BENCH_SAMPLES) {
            LOADED[sample] = elapsed;
        }
        if (sample != BENCH_SAMPLES - 1) {
            return false;
        }
        var planet = PlanetFlightProperties.in(level);
        for (var ship : benchmarkShips) {
            var motion = flight(ship).motion();
            require(planet.relativeDensityAt(motion.center().y) > 0 && motion.center().y > 100 && motion.velocity().y < 0,
                    "Benchmark ship did not remain in atmospheric descent");
            discard(ship);
        }
        double empty = AeroCacheTrial.medianMs(EMPTY);
        double loaded = AeroCacheTrial.medianMs(LOADED);
        LOGGER.info("REENTRY BENCH heat={} ships=10 nodes=63 samples={} emptyMs={} loadedMs={} incrementMs={}",
                ReentryHeating.ENABLED, BENCH_SAMPLES, empty, loaded, loaded - empty);
        return true;
    }

    private static boolean visual(ServerLevel level) {
        var position = new Vec3(0, 175, 0);
        if (ticks == 0) {
            visualShip = spawn(level, capsule(false, false), new Vec3(0, 100, 0));
            var viewer = level.players().getFirst();
            viewer.setGameMode(GameType.SPECTATOR);
            viewer.teleportTo(level, 6.4, 175.5, 7.3, 138.8F, 12.7F);
        }
        // Hold one shot while the normal thermal step receives a steady test air velocity.
        flight(visualShip).setMotion(new Motion(position, Vec3.ZERO, facingInto(VISUAL_AIR_VELOCITY), Vec3.ZERO));
        if (ticks == 160) {
            LOGGER.info("REENTRY VISUAL ratio={}", flight(visualShip).reentryHeating().heatRatio());
        }
        if (ticks < 360) {
            return false;
        }
        discard(visualShip);
        return true;
    }

    static Vec3 visualAirVelocity(AssembledShipEntity ship) {
        return ship == visualShip ? VISUAL_AIR_VELOCITY : null;
    }

    private static boolean staticShip(ServerLevel level) {
        var ship = spawn(level, capsule(true, false), new Vec3(0, 100, 0));
        require(!ship.isFlyingShip() && ship.flightControl() == null, "Expected a static capsule without Sable");
        ship.discard();
        LOGGER.info("REENTRY STATIC PASS without Sable");
        return true;
    }

    private static ShipDesign capsule(boolean shield, boolean wood) {
        var original = AeroTrialDesigns.capsule();
        if (!shield && !wood) {
            return original;
        }
        var state = (wood ? Blocks.OAK_PLANKS : Blocks.OBSIDIAN).defaultBlockState();
        var plate = new HashMap<BlockPos, BlockState>();
        for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 2, 0, 2)) {
            plate.put(pos.immutable(), state);
        }
        var front = wood ? Node.hull(HullRegion.capture(plate, BlockPos.ZERO), BlockPos.ZERO)
                : new Node(AeroTrialDesigns.bake(plate), PartPose.ROOT, -1);
        return new ShipDesign(List.of(front, original.nodes().get(1), original.nodes().get(2)), original.links(), 1);
    }

    private static AssembledShipEntity spawn(ServerLevel level, ShipDesign design, Vec3 origin) {
        require(design.isValidLayout(), "Invalid capsule fixture");
        level.getChunkAt(BlockPos.containing(origin));
        var blueprint = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        blueprint.set(BlockSpaceProgram.FLIGHT_LAYOUT, design);
        return Objects.requireNonNull(AssembledShipEntity.spawn(level, blueprint, origin, PartPose.ROOT.rotation(), null));
    }

    private static SableShipFlight flight(AssembledShipEntity ship) {
        return Objects.requireNonNull(ship.activeFlight(), "Fixture flight no longer active");
    }

    private static void discard(AssembledShipEntity ship) {
        var current = flight(ship);
        ship.discard();
        current.removeBodyNow();
    }

    private static Quaterniond facingInto(Vec3 velocity) {
        return new Quaterniond().rotationTo(new Vector3d(0, -1, 0), new Vector3d(velocity.x, velocity.y, velocity.z).normalize());
    }

    private static void command(ServerLevel level, String command) {
        var server = level.getServer();
        boolean[] success = {false};
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level)
                .withCallback((ok, result) -> success[0] = ok), command);
        require(success[0], "Fixture command failed: " + command);
    }

    private static void require(boolean success, String message) {
        if (!success) {
            throw new IllegalStateException(message);
        }
    }

    private static final class PathTrial {
        private static final String ORBIT = "bsp_ship orbit ";
        private static final String OVERWORLD = " minecraft:overworld ";
        private final String label;
        private final UUID id;
        private final boolean wood;
        private double entrySpeed;
        private double openArea;
        private boolean restored;
        private boolean burned;
        private boolean done;

        PathTrial(ServerLevel level, String label, boolean shield, boolean wood, boolean orbit, int offset) {
            this.label = label;
            this.wood = wood;
            var design = capsule(shield, wood);
            var ship = spawn(level, design, new Vec3(-64, 100, (orbit ? -1 : 1) * (offset + 1) * 32));
            id = ship.getUUID();
            if (orbit) {
                command(level, ORBIT + id + OVERWORLD + "70000 0 0 0 0 0");
                // An impulsive retrograde burn at apoapsis sets a 3 km periapsis. Separate flight paths in longitude.
                int periapsisAngle = 180 + 12 * offset;
                command(level, ORBIT + id + OVERWORLD + "66315.78947368421 0.05263157894736842 0 0 "
                        + periapsisAngle + " 180");
            } else {
                var velocity = new Vec3(960, -60, 0);
                var active = flight(ship);
                active.setMotion(new Motion(active.motion().center(), Vec3.ZERO, facingInto(velocity), Vec3.ZERO));
                command(level, "bsp_ship position " + id + " 0 5000 " + 128 * offset + " 960 -60 0");
            }
            LOGGER.info("REENTRY PATH {} mass={} id={}", label, design.fullMass(), id);
        }

        boolean tick(ServerLevel level) {
            if (done || !(level.getEntity(id) instanceof AssembledShipEntity ship)) {
                return done;
            }
            var flight = flight(ship);
            var motion = flight.motion();
            var heat = Objects.requireNonNull(flight.reentryHeating());
            if (!restored) {
                LOGGER.info("REENTRY START PATH {} y={} speed={} temperature={}", label, motion.center().y,
                        motion.velocity().length(), heat.samples().getFirst().peak);
                restored = true;
                openArea = heat.exposedArea(Side.NEG_Y, 1);
            }
            var planet = PlanetFlightProperties.in(level);
            if (entrySpeed == 0 && planet.relativeDensityAt(motion.center().y) > 0) {
                entrySpeed = motion.velocity().length();
                LOGGER.info("REENTRY ENTRY {} y={} speed={}", label, motion.center().y, entrySpeed);
                flight.setMotion(new Motion(motion.center(), motion.velocity(), facingInto(motion.velocity()), Vec3.ZERO));
            }
            if (wood && !burned && (flight.damagedParts() & 1) != 0) {
                burned = true;
                LOGGER.info("REENTRY BURN {} forwardAftArea={} -> {} rearTempPeakAtBurn={} burnSeconds={}", label,
                        openArea, heat.exposedArea(Side.NEG_Y, 1), heat.samples().get(1).peak, heat.samples().getFirst().firstOverheat);
            }
            if (motion.center().y > SEA_LEVEL + 500 || heat.heatRatio() > 1) {
                return false;
            }
            LOGGER.info("REENTRY END PATH {} y={} speed={}", label, motion.center().y, motion.velocity().length());
            var samples = heat.samples();
            for (int i = 0; i < samples.size(); i++) {
                var sample = samples.get(i);
                LOGGER.info("REENTRY RESULT {} node={} peak={} overheatSeconds={} firstExposure={} damaged={}", label, i,
                        sample.peak, sample.overheatSeconds, sample.firstExposure, (flight.damagedParts() & (1L << i)) != 0);
            }
            discard(ship);
            done = true;
            return true;
        }
    }
}
