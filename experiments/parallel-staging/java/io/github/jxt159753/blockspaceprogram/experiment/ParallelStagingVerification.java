package io.github.jxt159753.blockspaceprogram.experiment;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.PartPose;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.AttitudeMode;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.Telemetry;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.ThrottleMode;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightEvent;
import io.github.jxt159753.blockspaceprogram.assembly.ShipPlacement;
import io.github.jxt159753.blockspaceprogram.assembly.StagePerformance;
import io.github.jxt159753.blockspaceprogram.orbit.ManeuverNode;
import io.github.jxt159753.blockspaceprogram.orbit.Orbit;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitElements;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalFlightControl;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitingShip;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Quaterniond;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class ParallelStagingVerification {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("bsp.parallelStaging.verify");
    private static final int START = 20;
    private static final int SAMPLE = START + 60;
    private static final int TIMEOUT = START + 160;
    private static final GameProfile PILOT = new GameProfile(UUID.randomUUID(), "ParallelStaging");
    private static int ticks;
    private static boolean finished;
    private static AssembledShipEntity parallel;
    private static AssembledShipEntity opposed;
    private static AssembledShipEntity manual;
    private static AssembledShipEntity held;
    private static AssembledShipEntity saturated;
    private static OrbitSample orbital;
    private static Telemetry beforeSeparation;
    private static Telemetry afterSeparation;
    private static int separatedAt;

    private ParallelStagingVerification() {}

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        if (!ENABLED || finished || !(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld() || !level.getServer().isReady()) {
            return;
        }
        ticks++;
        try {
            if (ticks == START) {
                start(level);
            } else if (ticks > START) {
                if (ticks == SAMPLE) {
                    sampleOpposedAndOffset(level);
                }
                if (sampleParallel()) {
                    finish(level, "all dedicated server checks");
                }
                require(ticks < TIMEOUT, "Timed out waiting for the parallel staging flight");
            }
        } catch (RuntimeException error) {
            LOGGER.error("PARALLEL STAGING FAIL", error);
            finished = true;
            level.getServer().halt(false);
        }
    }

    @SubscribeEvent
    public static void staged(ShipFlightEvent event) {
        if (!ENABLED || event.ship() != parallel || event.kind() != ShipFlightEvent.Kind.STAGE_CHANGED) {
            return;
        }
        require(event.stage() == 1 && event.separatedShipIds().size() == 2, "Expected two separated boosters");
        require(event.ship().design().nodes().size() == 1, "Expected only the core to remain");
        afterSeparation = controls(event.ship()).telemetry();
        separatedAt = ticks;
    }

    private static void start(ServerLevel level) {
        boolean hasSable = ModList.get().isLoaded("sable");
        require(hasSable == Boolean.getBoolean("bsp.parallelStaging.expectSable"), "Unexpected Sable installation");
        var sideSeparator = BlockSpaceProgram.SEPARATION_INTERFACE.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.WEST);
        var pod = bake(Map.of(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState()));
        var core = bake(Map.of(
                BlockPos.ZERO, nozzle(Direction.DOWN),
                BlockPos.ZERO.above(), Blocks.IRON_BLOCK.defaultBlockState(),
                BlockPos.ZERO.above(2), Blocks.OAK_PLANKS.defaultBlockState()));
        var booster = bake(Map.of(BlockPos.ZERO, nozzle(Direction.DOWN), BlockPos.ZERO.above(), sideSeparator));
        var parallelDesign = attachPair(core, booster);
        require(parallelDesign.nodes().stream().allMatch(node -> node.engineStage() == 0), "Expected three ignition actions in stage 1");
        var performance = StagePerformance.calculate(parallelDesign);
        near(680, parallelDesign.fullMass(), "pad mass");
        near(1000 * Math.log(680.0 / 560), performance.getFirst().deltaV(), "stage 1 delta-v");
        near(1000 * Math.log(460.0 / 200), performance.getLast().deltaV(), "stage 2 delta-v");
        near(5, performance.getFirst().burnTime(), "stage 1 burn time");
        near(32.5, performance.getLast().burnTime(), "stage 2 burn time");
        LOGGER.info("PARALLEL STAGING VAB mirrors=2 stage1={} stage2={}", performance.getFirst(), performance.getLast());
        parallel = launch(level, 8, parallelDesign);
        if (!hasSable) {
            require(!parallel.isFlyingShip() && parallel.flightControl() == null, "Expected a static ship without Sable");
            finish(level, "static launch without Sable");
            return;
        }
        controls(parallel).setThrottle(ThrottleMode.DIRECT, 1);
        near(24_000, controls(parallel).telemetry().availableThrust(), "stage 1 combined thrust");

        var reversed = bake(Map.of(BlockPos.ZERO, sideSeparator, BlockPos.ZERO.east(), nozzle(Direction.EAST)));
        var opposedDesign = attachPair(pod, reversed);
        var opposedPerformance = StagePerformance.calculate(opposedDesign).getFirst();
        near(0, opposedPerformance.deltaV(), "opposed VAB delta-v");
        near(5, opposedPerformance.burnTime(), "opposed VAB burn time");
        LOGGER.info("PARALLEL STAGING VAB opposed deltaV={} burnSeconds={}",
                opposedPerformance.deltaV(), opposedPerformance.burnTime());
        opposed = launch(level, 40, opposedDesign);
        startAirborne(opposed, AttitudeMode.MANUAL, 1);
        orbital = queueOpposedManeuver(level, opposed);

        var engine = bake(Map.of(BlockPos.ZERO, nozzle(Direction.DOWN)));
        var offsetDesign = placeSide(root(pod), engine, -1, 1).design();
        manual = launch(level, 72, offsetDesign);
        held = launch(level, 104, offsetDesign);
        saturated = launch(level, 136, offsetDesign);
        startAirborne(manual, AttitudeMode.MANUAL, 0.01);
        startAirborne(held, AttitudeMode.HOLD, 0.01);
        startAirborne(saturated, AttitudeMode.HOLD, 1);
    }

    private static boolean sampleParallel() {
        var sample = controls(parallel).telemetry();
        if (beforeSeparation == null && sample.availableThrust() <= 8_000 && sample.fuel() < 261) {
            near(8_000, sample.availableThrust(), "thrust after booster depletion");
            beforeSeparation = sample;
            require(controls(parallel).requestStage(), "Could not request booster separation");
        }
        if (afterSeparation == null || ticks < Math.max(separatedAt + 5, SAMPLE)) {
            return false;
        }
        near(8_000, sample.availableThrust(), "stage 2 core thrust");
        near(8_000, sample.thrust(), "stage 2 applied thrust");
        LOGGER.info("PARALLEL STAGING FLIGHT separated={} altitude={} worldY={} speed={} coreFuel={} -> {} coreThrust={}",
                2, afterSeparation.altitude(), afterSeparation.position().y, afterSeparation.velocity().length(),
                afterSeparation.fuel(), sample.fuel(), sample.thrust());
        LOGGER.info("PARALLEL STAGING FLIGHT verticalSpeedBeforeCut={} verticalSpeedFiveTicksLater={}",
                beforeSeparation.verticalSpeed(), sample.verticalSpeed());
        require(sample.stage() == 1 && sample.fuel() < afterSeparation.fuel(), "Core did not keep burning in stage 2");
        return true;
    }

    private static OrbitSample queueOpposedManeuver(ServerLevel level, AssembledShipEntity physical) {
        var server = level.getServer();
        var data = OrbitalSavedData.get(server);
        var center = OrbitalSystem.MOTHER_PLANET;
        double now = data.orbitSeconds();
        var orbit = new Orbit(center, new OrbitElements(data.system().body(center).radius() + 10_000, 0, 0, 0, 0, 0, now));
        var id = UUID.randomUUID();
        var state = physical.saveWithoutId(new CompoundTag());
        data.put(new OrbitingShip(id, orbit, new Quaterniond(), Vec3.ZERO, state));
        var control = new OrbitalFlightControl(server, id, PILOT.getId());
        var node = new ManeuverNode(now + 1, new Vec3(5, 0, 0));
        var result = control.addManeuverNode(node);
        require(result == OrbitalSavedData.ManeuverResult.INSUFFICIENT_FUEL, "Canceled thrust must prevent maneuver planning");
        near(0, control.telemetry().flight().availableThrust(), "on-rails flight telemetry");
        // A maneuver queued before the prototype was enabled must also be rejected at ignition.
        data.put(new OrbitingShip(id, orbit, new Quaterniond(), Vec3.ZERO, state, Optional.of(node)));
        return new OrbitSample(id, orbit, state);
    }

    private static void sampleOpposedAndOffset(ServerLevel level) {
        var local = controls(opposed).telemetry();
        near(0, local.thrust(), "opposed thrust in Sable");
        require(local.fuel() < 80 && local.fuel() > 0, "Opposed engines should consume fuel");
        LOGGER.info("PARALLEL STAGING OPPOSED thrust={} remainingFuel={} initialFuel=80", local.thrust(), local.fuel());

        var data = OrbitalSavedData.get(level.getServer());
        var actual = data.remove(orbital.id());
        require(actual != null && actual.maneuver().isEmpty(), "Queued opposed maneuver did not reach ignition");
        double now = data.orbitSeconds();
        var expectedVelocity = data.system().relativeState(orbital.coast(), now).velocity();
        var actualVelocity = data.system().relativeState(actual.orbit(), now).velocity();
        double change = actualVelocity.distanceTo(expectedVelocity);
        near(0, change, "maneuver-induced velocity change");
        require(actual.shipData().equals(orbital.initialState()), "Rejected maneuver must not consume fuel");
        LOGGER.info("PARALLEL STAGING ORBIT deltaVRequested=5 deltaVApplied={} planner=INSUFFICIENT_FUEL", change);

        recordOffset("manual", manual);
        recordOffset("hold", held);
        recordOffset("hold-full", saturated);
        require(controls(manual).telemetry().angularVelocity().length() > 0.05, "Off-center thrust should turn the ship");
    }

    private static void recordOffset(String mode, AssembledShipEntity ship) {
        var sample = controls(ship).telemetry();
        var orientation = ship.activeFlight().motion().orientation();
        double angle = Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(orientation.w()))));
        LOGGER.info("PARALLEL STAGING OFFSET mode={} torqueSampleTicks=60 thrust={} angleDeg={} angularSpeed={}",
                mode, sample.thrust(), angle, sample.angularVelocity().length());
    }

    private static void startAirborne(AssembledShipEntity ship, AttitudeMode mode, double throttle) {
        var flight = ship.activeFlight();
        var center = flight.motion().center();
        flight.setMotion(new SableShipFlight.Motion(new Vec3(center.x, 220, center.z), Vec3.ZERO, new Quaterniond(), Vec3.ZERO));
        flight.setAttitudeMode(mode);
        flight.setThrottle(ThrottleMode.DIRECT, throttle);
    }

    private static ShipDesign attachPair(BakedPart core, BakedPart booster) {
        int marker = ShipPlacement.sources(booster, false).stream()
                .mapToInt(ShipPlacement.Source::marker)
                .filter(index -> index >= 0 && booster.connectors().get(index).kind() == PartConnector.Kind.SEPARATION)
                .findFirst().orElseThrow();
        var placement = placeSide(root(core), booster, marker, 2);
        var design = placement.design();
        var links = design.links().stream().map(link -> {
            require(link.surface() && link.separable(), "Expected a separable surface link");
            return new ShipDesign.Link(link.first(), link.second(), true, true, 1);
        }).toList();
        require(placement.skippedMirrors() == 0 && links.size() == 2, "Two-way surface symmetry failed");
        require(links.getFirst().first().normal() == Direction.EAST && links.getLast().first().normal() == Direction.WEST,
                "Boosters must attach to opposite sides of the core");
        return new ShipDesign(design.nodes(), links, design.stageCount());
    }

    private static ShipDesign root(BakedPart part) {
        int engineStage = part.properties().thrust() > 0 ? 0 : -1;
        return new ShipDesign(List.of(new ShipDesign.Node(part, PartPose.ROOT, engineStage)), List.of(), 2);
    }

    private static ShipPlacement.Result placeSide(ShipDesign design, BakedPart part, int marker, int symmetry) {
        var result = ShipPlacement.place(design, part, new ShipPlacement.Request(0, -1, marker, Direction.EAST,
                Direction.WEST, design.nodes().getFirst().bounds().getCenter(), 0, symmetry));
        require(result.error() == null, "Could not place fixture part: " + result.error());
        return result;
    }

    private static AssembledShipEntity launch(ServerLevel level, int x, ShipDesign design) {
        var pad = new BlockPos(x, 70, 8);
        level.setChunkForced(x >> 4, 0, true);
        level.getChunkAt(pad);
        var player = FakePlayerFactory.get(level, PILOT);
        player.setPos(x + 0.5, 71, 12.5);
        var blueprint = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        blueprint.set(BlockSpaceProgram.SHIP_DESIGN, design);
        for (int i = 0; i < design.nodes().size(); i++) {
            var part = new ItemStack(BlockSpaceProgram.BAKED_PART.get());
            part.set(BlockSpaceProgram.PART_DATA, design.nodes().get(i).part());
            player.getInventory().setItem(i, part);
        }
        var padState = BlockSpaceProgram.LAUNCH_PAD.get().defaultBlockState();
        level.setBlockAndUpdate(pad, padState);
        var hit = new BlockHitResult(Vec3.atCenterOf(pad), Direction.UP, pad, false);
        require(design.isValid(), "Fixture blueprint is invalid");
        require(padState.useItemOn(blueprint, level, player, InteractionHand.MAIN_HAND, hit) == ItemInteractionResult.CONSUME,
                "Launch pad refused the fixture at " + pad);
        for (var entity : level.getEntities().getAll()) {
            if (entity instanceof AssembledShipEntity ship && PILOT.getId().equals(ship.owner())
                    && ship.dimensionCenter().distanceTo(Vec3.atCenterOf(pad)) < 4) {
                return ship;
            }
        }
        throw new IllegalStateException("Launch did not create a ship");
    }

    private static ShipFlightControl controls(AssembledShipEntity ship) {
        var control = ship.flightControl();
        require(control != null, "Missing Sable flight controls");
        return control;
    }

    private static BlockState nozzle(Direction exhaust) {
        return Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, exhaust);
    }

    private static BakedPart bake(Map<BlockPos, BlockState> blocks) {
        return BakedPart.bake(blocks).orElseThrow();
    }

    private static void near(double expected, double actual, String description) {
        require(Math.abs(expected - actual) <= 1e-6, description + " expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }

    private static void finish(ServerLevel level, String result) {
        LOGGER.info("PARALLEL STAGING PASS {}", result);
        finished = true;
        level.getServer().halt(false);
    }

    private record OrbitSample(UUID id, Orbit coast, CompoundTag initialState) {}
}
