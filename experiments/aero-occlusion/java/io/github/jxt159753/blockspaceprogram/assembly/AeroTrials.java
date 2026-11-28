package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Side;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight.Motion;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.AttitudeMode;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.ThrottleMode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Reproducible dedicated-server runs using the ordinary Sable bodies, staging and controls. */
@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class AeroTrials {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean VERIFY = Boolean.getBoolean("bsp.aeroOcclusion.verify");
    private static final boolean BASELINE = Boolean.getBoolean("bsp.aeroOcclusion.baseline");
    private static final boolean EXPECT_SABLE = Boolean.getBoolean("bsp.aeroOcclusion.expectSable");
    private static final double SEA_LEVEL = 63;
    private static final int[] TRACE_HEIGHTS = {4_000, 3_000, 2_000, 1_000, 500, 100};
    private static final int MAX_FLIGHT_TICKS = 3_600;
    private static final int BENCH_WARMUP = 60;
    private static final int BENCH_SAMPLES = 160;
    private static final List<AssembledShipEntity> FIXTURE_SHIPS = new ArrayList<>();
    private static Run run;
    private static int readyTicks;
    private static long tickStart;
    private static boolean finished;

    private AeroTrials() {}

    @SubscribeEvent
    public static void beforeTick(ServerTickEvent.Pre event) {
        if (run != null) {
            tickStart = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void afterTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (!VERIFY || finished || !server.isDedicatedServer() || !server.isReady()) {
            return;
        }
        try {
            if (run == null) {
                if (++readyTicks < 80) {
                    return;
                }
                boolean sable = ModList.get().isLoaded("sable");
                require(sable == EXPECT_SABLE, "Unexpected Sable installation");
                var level = server.overworld();
                require(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) == SEA_LEVEL,
                        "Fixture world needs flat ground at Y=63");
                if (!sable) {
                    var ship = spawn(level, AeroTrialDesigns.rocket(), new Vec3(0, 66, 0));
                    require(ship.flightControl() == null, "Expected a static ship without Sable");
                    finish(level, "AERO PASS static flight without Sable");
                    return;
                }
                LOGGER.info("AERO START baseline={} clientCount={} torqueMultiplier={}", BASELINE, server.getPlayerCount(),
                        ShipServerConfig.AERO_TORQUE_MULTIPLIER.get());
                server.tickRateManager().requestGameToSprint(MAX_FLIGHT_TICKS + 1_200);
                run = new Run(level);
            } else if (run.tick(System.nanoTime() - tickStart)) {
                finish(run.level, run.shallow.touched && run.sideways.touched
                        ? "AERO PASS all dedicated-server trials" : "AERO INCOMPLETE contact verification");
            }
        } catch (RuntimeException | IOException error) {
            LOGGER.error("AERO FAIL", error);
            finish(server.overworld(), "AERO STOP failed fixture");
        }
    }

    @SubscribeEvent
    public static void stageChanged(ShipFlightEvent event) {
        if (run != null && event.kind() == ShipFlightEvent.Kind.STAGE_CHANGED) {
            run.recordStage(event);
        }
    }

    private static void finish(ServerLevel level, String result) {
        FIXTURE_SHIPS.forEach(AeroTrials::discardFixture);
        LOGGER.info(result);
        finished = true;
        level.getServer().halt(false);
    }

    private static AssembledShipEntity spawn(ServerLevel level, ShipDesign design, Vec3 origin) {
        require(design.isValidLayout(), "Cannot launch an invalid fixture layout");
        level.getChunkAt(BlockPos.containing(origin));
        var blueprint = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        blueprint.set(BlockSpaceProgram.FLIGHT_LAYOUT, design);
        var ship = AssembledShipEntity.spawn(level, blueprint, origin, PartPose.ROOT.rotation(), null);
        require(ship != null, "Could not launch fixture ship");
        FIXTURE_SHIPS.add(ship);
        return ship;
    }

    private static SableShipFlight flight(AssembledShipEntity ship) {
        var flight = ship.activeFlight();
        require(flight != null && flight.isActive(), "Fixture flight no longer active: " + ship.getUUID());
        return flight;
    }

    private static void discardFixture(AssembledShipEntity ship) {
        if (!ship.isRemoved()) {
            var current = ship.activeFlight();
            ship.discard();
            if (current != null) {
                current.removeBodyNow();
            }
        }
    }

    private static void require(boolean value, String description) {
        if (!value) {
            throw new IllegalStateException(description);
        }
    }

    private static final class Run {
        final ServerLevel level;
        final Descending shallow;
        final Descending sideways;
        final AssembledShipEntity rocket;
        final AssembledShipEntity lowerDock;
        final AssembledShipEntity upperDock;
        final ShipDesign benchmark;
        final List<AssembledShipEntity> fairingFragments = new ArrayList<>();
        final List<AssembledShipEntity> benchmarkShips = new ArrayList<>();
        final long[] empty = new long[BENCH_SAMPLES];
        final long[] loaded = new long[BENCH_SAMPLES];
        AssembledShipEntity fairing;
        AssembledShipEntity splitBenchmark;
        int ticks;
        int benchmarkTicks;
        boolean rocketDone;
        boolean upperStageRequested;
        boolean splitDone;
        double maxAltitude;
        double maxTilt;

        Run(ServerLevel level) throws IOException {
            this.level = level;
            var capsule = AeroTrialDesigns.capsule();
            require(capsule.fullMass() == 5_400, "The test capsule must weigh 5,400 kg");
            // Aim both trajectories at the same flat landing area despite their different ranges.
            int shallowStartX = BASELINE ? -8_200 : -8_136;
            shallow = new Descending(capsule, "shallow", shallowStartX, 8, new Vec3(745, -30, 0), new Quaterniond());
            sideways = new Descending(capsule, "sideways", 0, 24, new Vec3(0, -300, 0),
                    new Quaterniond().rotationZ(Math.PI / 2));

            var design = AeroTrialDesigns.rocket();
            var performance = StagePerformance.calculate(design);
            LOGGER.info("AERO ROCKET fixtureMass={} stagePerformance={}", design.fullMass(), performance);
            rocket = spawn(level, design, new Vec3(16, 75, -12));
            var launch = flight(rocket);
            var initial = launch.motion();
            launch.setMotion(new Motion(initial.center(), Vec3.ZERO, new Quaterniond().rotationZ(Math.toRadians(1)), Vec3.ZERO));
            launch.holdAttitude(0, 0, 0);
            launch.claimRemote(() -> true);
            launch.setThrottle(ThrottleMode.DIRECT, 1);

            benchmark = AeroTrialDesigns.benchmark();
            lowerDock = BASELINE ? null : spawn(level, AeroTrialDesigns.dockingPod(Direction.UP), new Vec3(32, 110, -16));
            upperDock = BASELINE ? null : spawn(level, AeroTrialDesigns.dockingPod(Direction.DOWN), new Vec3(32, 112.125, -16));
            if (!BASELINE) {
                require(flight(lowerDock).aerodynamics().exposedArea(Side.POS_Y, 0) == 1
                        && flight(upperDock).aerodynamics().exposedArea(Side.POS_Y, 0) == 1, "Expected two exposed docking pods");
                AeroCacheTrial.run(LOGGER, benchmark);
                var payload = AeroTrialDesigns.payload();
                var uncovered = ShipAerodynamics.project(payload, new BitSet());
                fairing = spawn(level, AeroTrialDesigns.fairings(payload), new Vec3(8, 115, -8));
                var covered = flight(fairing).aerodynamics();
                recordFairing("bare", uncovered);
                recordFairing("covered", covered);
                require(covered.exposedArea(Side.POS_Y, 1) == 0 && uncovered.exposedArea(Side.POS_Y, 1) == 2,
                        "Fairings did not shield the payload");
                require(flight(fairing).requestStage(), "Fairing stage missing");
            }
        }

        boolean tick(long elapsed) {
            ticks++;
            if (fairing != null && ticks == 24) {
                checkDockedProjection();
                require(fairingFragments.size() == 2, "Expected two detached fairing halves");
                var left = flight(fairingFragments.get(0)).motion();
                var right = flight(fairingFragments.get(1)).motion();
                require(Math.abs(left.center().x - right.center().x) > 2.0, "Fairing halves did not move apart");
                LOGGER.info("AERO FAIRING detachedCount={} gapBetweenCenters={} relativeSpeed={}", fairingFragments.size(),
                        Math.abs(left.center().x - right.center().x), left.velocity().subtract(right.velocity()).length());
                fairingFragments.forEach(AeroTrials::discardFixture);
                discardFixture(fairing);
            }
            shallow.sample();
            sideways.sample();
            sampleRocket();
            if (!(shallow.done && sideways.done && rocketDone)) {
                require(ticks < MAX_FLIGHT_TICKS, "Timed out before both contacts and rocket apogee");
                return false;
            }
            return sampleBenchmark(elapsed);
        }

        private void checkDockedProjection() {
            require(lowerDock.isRemoved() != upperDock.isRemoved(), "Expected the pods to dock");
            var merged = lowerDock.isRemoved() ? upperDock : lowerDock;
            var nodes = merged.design().nodes();
            require(nodes.size() == 2, "Expected two nodes after docking");
            int lower = nodes.get(0).pose().position().y < nodes.get(1).pose().position().y ? 0 : 1;
            var projection = flight(merged).aerodynamics();
            double lowerArea = projection.exposedArea(Side.POS_Y, lower);
            double upperArea = projection.exposedArea(Side.POS_Y, 1 - lower);
            require(projection.area(Side.POS_Y) == 1 && lowerArea == 0 && upperArea == 1,
                    "Docked upper pod should shield the lower pod");
            LOGGER.info("AERO DOCK direction=+Y individualArea=1.0 lowerArea={} upperArea={} totalArea={}",
                    lowerArea, upperArea, projection.area(Side.POS_Y));
            discardFixture(merged);
        }

        private void sampleRocket() {
            if (rocketDone) {
                return;
            }
            var current = flight(rocket);
            var motion = current.motion();
            var telemetry = current.telemetry();
            double altitude = motion.center().y - SEA_LEVEL;
            if (upperStageRequested && telemetry.stage() == 1 && telemetry.fuel() < 1e-6 && motion.velocity().y <= 0
                    || telemetry.landed() && ticks > 20) {
                LOGGER.info("AERO ROCKET apogee={} maxTilt={} wheelSaturatedSubsteps={} stage={} fuel={} ticks={}",
                        maxAltitude, maxTilt, current.wheelSaturationSteps(), telemetry.stage(), telemetry.fuel(), ticks);
                discardFixture(rocket);
                rocketDone = true;
                return;
            }
            maxAltitude = Math.max(maxAltitude, altitude);
            maxTilt = Math.max(maxTilt, angle(motion.orientation().transform(new Vector3d(0, 1, 0)).y));
            if (!upperStageRequested && telemetry.fuel() <= 100.000001 && telemetry.availableThrust() == 0) {
                require(current.requestStage(), "Upper rocket stage missing");
                upperStageRequested = true;
            }
            if (ticks % 200 == 0) {
                LOGGER.info("AERO ROCKET t={} altitude={} verticalSpeed={} stage={} fuel={} saturated={}",
                        ticks / 20.0, altitude, motion.velocity().y, telemetry.stage(), telemetry.fuel(),
                        current.wheelSaturationSteps());
            }
        }

        private boolean sampleBenchmark(long elapsed) {
            int step = benchmarkTicks++;
            if (step == 0 && !BASELINE) {
                splitBenchmark = spawn(level, benchmark, new Vec3(32, 90, 16));
                require(flight(splitBenchmark).requestStage(), "63-node fixture cut missing");
            }
            if (step == BENCH_WARMUP) {
                require(BASELINE || splitDone, "63-node fixture did not split");
            }
            if (step >= BENCH_WARMUP && step < BENCH_WARMUP + BENCH_SAMPLES) {
                empty[step - BENCH_WARMUP] = elapsed;
            }
            int loadStep = BENCH_WARMUP + BENCH_SAMPLES;
            if (step == loadStep) {
                for (int i = 0; i < 10; i++) {
                    int x = 32 * (i % 5);
                    int z = 32 + 32 * (i / 5);
                    var ship = spawn(level, benchmark, new Vec3(x, 100, z));
                    var current = flight(ship);
                    var start = current.motion().center();
                    current.setMotion(new Motion(new Vec3(start.x, 3_100, start.z), new Vec3(0, -15, 0),
                            new Quaterniond().rotationX(Math.toRadians(5)), Vec3.ZERO));
                    benchmarkShips.add(ship);
                }
            }
            int index = step - loadStep - BENCH_WARMUP;
            if (index >= 0 && index < BENCH_SAMPLES) {
                loaded[index] = elapsed;
            }
            if (index != BENCH_SAMPLES - 1) {
                return false;
            }
            for (var ship : benchmarkShips) {
                var motion = flight(ship).motion();
                require(motion.center().y < 3_100 && motion.center().y > 63 && motion.velocity().y < 0,
                        "Benchmark ship did not remain in atmospheric descent");
                discardFixture(ship);
            }
            double emptyMs = AeroCacheTrial.medianMs(empty);
            double loadedMs = AeroCacheTrial.medianMs(loaded);
            LOGGER.info("AERO BENCH ships=10 nodesPerShip={} ticksPerWindow={} emptyMs={} loadedMs={} incrementMs={}",
                    benchmark.nodes().size(), BENCH_SAMPLES, emptyMs, loadedMs, loadedMs - emptyMs);
            return true;
        }

        void recordStage(ShipFlightEvent event) {
            if (event.ship() == rocket) {
                LOGGER.info("AERO ROCKET staged t={} stage={} debris={}", ticks / 20.0, event.stage(), event.separatedShipIds().size());
                for (var id : event.separatedShipIds()) {
                    if (level.getEntity(id) instanceof AssembledShipEntity debris) {
                        // Exclude the spent stage from the subsequent performance comparison.
                        level.getServer().execute(() -> discardFixture(debris));
                    }
                }
            }
            if (event.ship() != fairing && event.ship() != splitBenchmark) {
                return;
            }
            var fragments = event.separatedShipIds().stream().map(id -> (AssembledShipEntity) level.getEntity(id)).toList();
            FIXTURE_SHIPS.addAll(fragments);
            if (event.ship() == fairing) {
                fairingFragments.addAll(fragments);
                var projection = flight(fairing).aerodynamics();
                require(projection.exposedArea(Side.POS_Y, 1) == 2, "Payload must be exposed after staging");
                recordFairing("jettisoned", projection);
            } else {
                require(fragments.size() == 1, "Expected exactly one 63-node fixture fragment");
                var detached = fragments.getFirst();
                int remaining = splitBenchmark.design().nodes().size();
                int removed = detached.design().nodes().size();
                require(remaining + removed == 63, "Expected 63 nodes across both fragments");
                var main = flight(splitBenchmark);
                var fragment = flight(detached);
                double mainArea = main.aerodynamics().area(Side.POS_Y);
                double detachedArea = fragment.aerodynamics().area(Side.POS_Y);
                require(mainArea == 10 && detachedArea == 10, "Expected independent silhouettes after staging");
                double mainMs = main.projectionBuildNanos() / 1e6;
                double detachedMs = fragment.projectionBuildNanos() / 1e6;
                LOGGER.info("AERO SPLIT remainingNodes={} detachedNodes={} remainingArea={} detachedArea={}"
                                + " remainingMs={} detachedMs={} totalMs={}",
                        remaining, removed, mainArea, detachedArea, mainMs, detachedMs, mainMs + detachedMs);
                level.getServer().execute(() -> {
                    discardFixture(detached);
                    discardFixture(splitBenchmark);
                });
                splitDone = true;
            }
        }

        private void recordFairing(String configuration, AeroProjection projection) {
            LOGGER.info("AERO FAIRING configuration={} frontalArea={} dragArea={} payloadExposure={}",
                    configuration, projection.area(Side.POS_Y), projection.dragArea(new Vector3d(0, 1, 0)),
                    projection.exposedArea(Side.POS_Y, 1));
        }

        private final class Descending {
            final String name;
            final AssembledShipEntity ship;
            Motion previous;
            double previousBottom;
            double firstAlignment = Double.NaN;
            boolean landingChunksLoaded;
            boolean impactMeasured;
            boolean done;
            boolean touched;

            Descending(ShipDesign design, String name, int x, int z, Vec3 velocity, Quaterniond orientation) {
                this.name = name;
                double startY = SEA_LEVEL + 4_999;
                previous = new Motion(new Vec3(x, startY, z), velocity, orientation, Vec3.ZERO);
                ship = spawn(level, design, new Vec3(x, 85, z));
                var current = flight(ship);
                current.setMotion(previous);
                current.claimRemote(() -> true);
                current.setAttitudeMode(AttitudeMode.MANUAL);
                previousBottom = current.worldBounds().minY;
                LOGGER.info("AERO DROP fixture={} t=0.0 altitude={} speed={} vx={} vy={} bottomAngle={} startX={}",
                        name, startY - SEA_LEVEL, velocity.length(), velocity.x, velocity.y, bottomAngle(previous), x);
            }

            void sample() {
                if (done) {
                    return;
                }
                var current = flight(ship);
                Motion motion = current.motion();
                double altitude = motion.center().y - SEA_LEVEL;
                if (!landingChunksLoaded && altitude < 150) {
                    // Keep the touchdown area active independently of players' view distance.
                    var position = BlockPos.containing(motion.center());
                    int chunkX = position.getX() >> 4;
                    int chunkZ = position.getZ() >> 4;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            level.setChunkForced(chunkX + dx, chunkZ + dz, true);
                        }
                    }
                    landingChunksLoaded = true;
                }
                double bottomAngle = bottomAngle(motion);
                if (Double.isNaN(firstAlignment) && bottomAngle <= 15) {
                    firstAlignment = ticks / 20.0;
                }
                for (int height : TRACE_HEIGHTS) {
                    if (previous.center().y - SEA_LEVEL > height && altitude <= height) {
                        double fraction = (previous.center().y - SEA_LEVEL - height) / (previous.center().y - motion.center().y);
                        var velocity = previous.velocity().lerp(motion.velocity(), fraction);
                        LOGGER.info("AERO DROP fixture={} t={} altitude={} speed={} vx={} vy={} bottomAngle={}",
                                name, (ticks - 1 + fraction) / 20.0, height, velocity.length(), velocity.x, velocity.y, bottomAngle);
                    }
                }
                double bottom = current.worldBounds().minY;
                long contact = current.lastContactTick();
                // The first impact changes the velocity before Sable may report a block contact callback.
                if (!impactMeasured && altitude < 4 && previous.velocity().y < 0
                        && motion.velocity().y - previous.velocity().y > 2) {
                    LOGGER.info("AERO IMPACT fixture={} t={} speedBeforeTick={} speedAfterTick={} bottomAngleBeforeTick={}"
                                    + " firstBelow15s={} centerHeight={} bottomY={}",
                            name, ticks / 20.0, previous.velocity().length(), motion.velocity().length(), bottomAngle(previous),
                            firstAlignment, altitude, bottom);
                    impactMeasured = true;
                }
                if (impactMeasured && contact != Long.MIN_VALUE) {
                    LOGGER.info("AERO TOUCH fixture={} t={} contactGameTick={}", name, ticks / 20.0, contact);
                    discardFixture(ship);
                    done = true;
                    touched = true;
                } else if (!impactMeasured && previousBottom > SEA_LEVEL && bottom <= SEA_LEVEL) {
                    double fraction = (previousBottom - SEA_LEVEL) / (previousBottom - bottom);
                    LOGGER.info("AERO SURFACE fixture={} t={} interpolatedSpeed={}", name,
                            (ticks - 1 + fraction) / 20.0, previous.velocity().lerp(motion.velocity(), fraction).length());
                }
                if (!done && altitude < -8) {
                    LOGGER.error("AERO CONTACT MISSED fixture={} position={} lastSpeed={} currentSpeed={}",
                            name, motion.center(), previous.velocity().length(), motion.velocity().length());
                    discardFixture(ship);
                    done = true;
                }
                previous = motion;
                previousBottom = bottom;
            }
        }
    }

    private static double bottomAngle(Motion motion) {
        var bottom = motion.orientation().transform(new Vector3d(0, -1, 0));
        var velocity = motion.velocity().normalize();
        return angle(bottom.dot(velocity.x, velocity.y, velocity.z));
    }

    private static double angle(double dot) {
        return Math.toDegrees(Math.acos(Math.clamp(dot, -1, 1)));
    }
}
