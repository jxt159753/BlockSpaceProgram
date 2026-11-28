package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.RcsAllocation.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Runs the same layouts and input profiles with A and B in the ordinary Sable and docking paths. */
public final class RcsServerTrial implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CC_ID = 138;
    private static final int WARMUP = 60;
    private static final int TICK_SAMPLES = 160;
    private static final int BENCHMARK_SHIPS = 10;
    private final ServerLevel space;
    private final Vec3 origin;
    private final UUID owner;
    private final List<AssembledShipEntity> ships = new ArrayList<>();
    private int phase;
    private int ticks;
    private Scenario scenario;
    private long[] emptyTicks;

    public RcsServerTrial(ServerLevel space, Vec3 origin, UUID owner) {
        this.space = space;
        this.origin = origin;
        this.owner = owner;
        benchmarkSolvers();
    }

    public static void verifyStatic(ServerLevel level) {
        var ship = spawn(level, RcsTrialDesigns.shuttle(Direction.EAST), new Vec3(8, 110, 8), null);
        if (ship.flightControl() != null) {
            throw new IllegalStateException("Expected a static ship without Sable");
        }
        ship.discard();
        LOGGER.info("RCS PASS static launch without Sable");
    }

    public boolean tick(long tickNanos) throws IOException {
        if (scenario == null) {
            scenario = switch (phase) {
                case 0 -> new Docking(Method.PROJECTION);
                case 1 -> new Docking(Method.LEAST_SQUARES);
                case 2 -> new Translate(1);
                case 3 -> new Translate(0.01);
                case 4 -> computerCraft();
                case 5 -> tickBenchmark(false);
                case 6 -> tickBenchmark(true);
                default -> throw new IllegalStateException("Unknown RCS trial phase " + phase);
            };
        } else if (scenario.sample(++ticks, tickNanos)) {
            close();
            scenario = null;
            ticks = 0;
            phase++;
        }
        return phase == 7;
    }

    @Override
    public void close() {
        for (var ship : ships) {
            if (!ship.isRemoved()) {
                var flight = ship.activeFlight();
                ship.discard();
                if (flight != null) {
                    flight.removeBodyNow();
                }
            }
        }
        ships.clear();
    }

    private AssembledShipEntity launch(Direction port, Vec3 offset, Method method, int jets, double fuelFraction) {
        var design = RcsTrialDesigns.shuttle(port);
        var ship = spawn(space, design, origin.add(offset), owner);
        ships.add(ship);
        var flight = active(ship);
        flight.setMotion(flight.motion());
        if (jets > 0) {
            flight.installRcsTrial(new RcsFlightTrial(design, method, RcsTrialDesigns.mounts(jets)), fuelFraction);
        }
        return ship;
    }

    private static AssembledShipEntity spawn(ServerLevel level, ShipDesign design, Vec3 origin, UUID owner) {
        level.getChunkAt(BlockPos.containing(origin));
        var blueprint = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        blueprint.set(BlockSpaceProgram.FLIGHT_LAYOUT, design);
        return Objects.requireNonNull(AssembledShipEntity.spawn(level, blueprint, origin, PartPose.ROOT.rotation(), owner),
                "Could not launch an RCS fixture");
    }

    private static SableShipFlight active(AssembledShipEntity ship) {
        var flight = ship.activeFlight();
        if (flight == null || !flight.isActive()) {
            throw new IllegalStateException("Fixture is no longer in local physics " + ship.getUUID());
        }
        return flight;
    }

    @FunctionalInterface
    private interface Scenario {
        boolean sample(int tick, long tickNanos) throws IOException;
    }

    private final class Docking implements Scenario {
        private final Method method;
        private final AssembledShipEntity chaser;
        private final AssembledShipEntity target;
        private final Vec3 chaserPort;
        private final Vec3 targetPort;
        private final double startFuel;
        private double maxAttitude;
        private double closest = Double.POSITIVE_INFINITY;
        private double lastGap;
        private double lastRelativeSpeed;

        Docking(Method method) {
            this.method = method;
            chaser = launch(Direction.EAST, Vec3.ZERO, method, 12, 1);
            target = launch(Direction.WEST, new Vec3(8, 0, 0), method, 0, 1);
            chaserPort = ShipDocking.openPorts(chaser.design(), 0, Set.of()).getFirst().center();
            targetPort = ShipDocking.openPorts(target.design(), 0, Set.of()).getFirst().center();
            startFuel = active(chaser).propellant().fuel() + active(target).propellant().fuel();
            active(chaser).holdAttitude(0, 0, 0);
            active(chaser).rcsTrial().setEnabled(true);
        }

        @Override
        public boolean sample(int tick, long tickNanos) {
            if (chaser.isRemoved() || target.isRemoved()) {
                var survivor = chaser.isRemoved() ? target : chaser;
                if (survivor.isRemoved() || survivor.design().nodes().size() != 4) {
                    throw new IllegalStateException("A docking fixture disappeared before capture");
                }
                LOGGER.info("RCS DOCK method={} captured=true seconds={} fuelKg={} maxAttitudeDeg={} lastGapM={} relativeSpeedMps={}",
                        method, tick / 20.0, startFuel - active(survivor).propellant().fuel(), maxAttitude, lastGap, lastRelativeSpeed);
                return true;
            }
            var flight = active(chaser);
            var motion = flight.motion();
            var targetFlight = active(target);
            var gap = targetFlight.worldPoint(targetPort).subtract(flight.worldPoint(chaserPort));
            var relativeVelocity = motion.velocity().subtract(targetFlight.motion().velocity());
            lastGap = gap.length();
            lastRelativeSpeed = relativeVelocity.length();
            closest = Math.min(closest, lastGap);
            maxAttitude = Math.max(maxAttitude, Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(motion.orientation().w()), 0, 1))));
            if (tick >= 800) {
                LOGGER.warn("RCS DOCK method={} captured=false seconds={} fuelKg={} maxAttitudeDeg={} closestGapM={}",
                        method, tick / 20.0, startFuel - flight.propellant().fuel() - targetFlight.propellant().fuel(), maxAttitude, closest);
                return true;
            }
            Vec3 desiredVelocity = gap.scale(Math.min(0.8, 0.37 / Math.max(lastGap, 0.001)));
            Vec3 desiredForce = desiredVelocity.subtract(relativeVelocity).scale(3 * flight.telemetry().mass() / 700);
            var input = motion.orientation().transformInverse(new Vector3d(desiredForce.x, desiredForce.y, desiredForce.z));
            input.div(Math.max(1, Math.max(Math.abs(input.x), Math.max(Math.abs(input.y), Math.abs(input.z)))));
            flight.rcsTrial().setTranslation(input.x, input.y, input.z);
            return false;
        }
    }

    private final class Translate implements Scenario {
        private final SableShipFlight flight;
        private final double fuel;
        private final Vec3 start;
        private final Vector3d center;
        private double maxRate;

        Translate(double fuelFraction) {
            flight = active(launch(Direction.EAST, Vec3.ZERO, Method.LEAST_SQUARES, 12, fuelFraction));
            fuel = flight.propellant().fuel();
            start = flight.motion().center();
            center = flight.trialCenterOfMass();
            flight.setAxes(0, 0, 0);
            flight.rcsTrial().setEnabled(true);
            flight.rcsTrial().setTranslation(0.35, 0, 0);
        }

        @Override
        public boolean sample(int tick, long tickNanos) {
            var motion = flight.motion();
            var rate = motion.angularVelocity().length();
            maxRate = Math.max(maxRate, rate);
            if (tick < 200) {
                return false;
            }
            LOGGER.info("RCS CENTER startFuelKg={} startCenter={} endCenter={} distanceX={} rateRadPerSec={} maxRateRadPerSec={}",
                    fuel, center, flight.trialCenterOfMass(), motion.center().x - start.x, rate, maxRate);
            return true;
        }
    }

    private Scenario computerCraft() throws IOException {
        var ship = launch(Direction.EAST, Vec3.ZERO, Method.LEAST_SQUARES, 12, 1);
        var ground = space.getServer().overworld();
        Path directory = ground.getServer().getWorldPath(LevelResource.ROOT).resolve("computercraft/computer/" + CC_ID);
        Files.createDirectories(directory);
        Path result = directory.resolve("rcs-result.txt");
        Files.deleteIfExists(result);
        try (var script = Objects.requireNonNull(getClass().getResourceAsStream("/rcs-trial/cc-startup.lua"))) {
            Files.copy(script, directory.resolve("startup.lua"), StandardCopyOption.REPLACE_EXISTING);
        }
        var spawn = ground.getSharedSpawnPos();
        var chunk = new ChunkPos(spawn);
        var pos = new BlockPos(chunk.getMinBlockX() + 4, spawn.getY() + 6, chunk.getMinBlockZ() + 4);
        // The overworld stops ticking block entities when the only observer stays in orbit.
        boolean forcedForTrial = ground.setChunkForced(chunk.x, chunk.z, true);
        ground.setBlockAndUpdate(pos, BlockSpaceProgram.FLIGHT_TERMINAL.get().defaultBlockState());
        var terminal = (FlightTerminalBlockEntity) Objects.requireNonNull(ground.getBlockEntity(pos));
        var binding = new CompoundTag();
        binding.putUUID("Owner", owner);
        binding.putUUID("Ship", ship.getUUID());
        terminal.loadAdditional(binding, ground.registryAccess());
        var computerPos = pos.east();
        var computerBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("computercraft", "computer_normal"));
        ground.setBlockAndUpdate(computerPos, computerBlock.defaultBlockState());
        var computer = Objects.requireNonNull(ground.getBlockEntity(computerPos), "CC:Tweaked is required by the Lua fixture");
        var configuration = new CompoundTag();
        configuration.putInt("ComputerId", CC_ID);
        configuration.putBoolean("On", true);
        computer.loadWithComponents(configuration, ground.registryAccess());
        return (tick, elapsed) -> {
            if (!Files.isRegularFile(result) && tick < 600) {
                return false;
            }
            String report = Files.isRegularFile(result) ? Files.readString(result).strip() : "FAIL timed out";
            if (report.isEmpty() && tick < 600) {
                return false;
            }
            LOGGER.info("RCS CC {}", report);
            ground.removeBlock(computerPos, false);
            ground.removeBlock(pos, false);
            if (forcedForTrial) {
                ground.setChunkForced(chunk.x, chunk.z, false);
            }
            if (!report.startsWith("PASS ")) {
                throw new IllegalStateException("RCS Lua check failed " + report);
            }
            return true;
        };
    }

    private Scenario tickBenchmark(boolean loaded) {
        var trials = new ArrayList<RcsFlightTrial>();
        if (loaded) {
            for (int i = 0; i < BENCHMARK_SHIPS; i++) {
                var ship = launch(Direction.EAST, new Vec3(8 * (i % 5) - 16, 0, 12 + 8 * (i / 5)), Method.LEAST_SQUARES, 64, 1);
                var trial = active(ship).rcsTrial();
                trial.setEnabled(true);
                double sign = i % 2 == 0 ? 1 : -1;
                trial.setTranslation(0.21 * sign, 0.12, -0.16);
                trials.add(trial);
            }
        }
        long[] times = new long[TICK_SAMPLES];
        long[] solves = new long[TICK_SAMPLES];
        return (tick, elapsed) -> {
            if (tick <= WARMUP) {
                return false;
            }
            int sample = tick - WARMUP - 1;
            times[sample] = elapsed;
            for (var trial : trials) {
                long solve = trial.solveNanos(space.getServer().getTickCount());
                if (solve <= 0) {
                    throw new IllegalStateException("Missing an active fixture solve at tick " + space.getServer().getTickCount());
                }
                solves[sample] += solve;
            }
            if (sample + 1 < TICK_SAMPLES) {
                return false;
            }
            if (!loaded) {
                emptyTicks = times;
            } else {
                LOGGER.info("RCS TICKS samples={} ships={} jetsEach=64 emptyP50Ms={} emptyP95Ms={} loadedP50Ms={} loadedP95Ms={} solveP50Ms={} solveP95Ms={}",
                        TICK_SAMPLES, trials.size(), percentile(emptyTicks, 0.50) / 1e6, percentile(emptyTicks, 0.95) / 1e6,
                        percentile(times, 0.50) / 1e6, percentile(times, 0.95) / 1e6,
                        percentile(solves, 0.50) / 1e6, percentile(solves, 0.95) / 1e6);
            }
            return true;
        };
    }

    private static void benchmarkSolvers() {
        for (var method : Method.values()) {
            for (int count : new int[]{8, 16, 32, 64}) {
                var center = new Vector3d(1.5, 0.53, 1.5);
                var jets = RcsTrialDesigns.mounts(count).stream().map(mount -> new RcsAllocation.Jet(mount.force(),
                        mount.point().sub(center, new Vector3d()).cross(mount.force()),
                        StagePerformance.fuelFlow(mount.force().length(), mount.isp()))).toList();
                long[] samples = new long[1_600];
                double checksum = 0;
                for (int sample = -4_000; sample < samples.length; sample++) {
                    var force = new Vector3d(4 * (sample % 91), 2 * (sample % 81), -3 * (sample % 79));
                    var torque = new Vector3d(400, -200, 150);
                    long start = System.nanoTime();
                    var allocated = RcsAllocation.allocate(method, jets, force, torque, 250);
                    long elapsed = System.nanoTime() - start;
                    if (sample >= 0) {
                        samples[sample] = elapsed;
                        checksum += allocated.fuelFlow() + allocated.force().x();
                    }
                }
                LOGGER.info("RCS SOLVE method={} jets={} samples={} p50Us={} p95Us={} checksum={}",
                        method, count, samples.length, percentile(samples, 0.50) / 1e3, percentile(samples, 0.95) / 1e3, checksum);
            }
        }
    }

    private static long percentile(long[] values, double fraction) {
        Arrays.sort(values);
        return values[(int) Math.ceil(values.length * fraction) - 1];
    }
}
