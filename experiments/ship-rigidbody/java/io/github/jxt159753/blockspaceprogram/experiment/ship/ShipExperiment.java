package io.github.jxt159753.blockspaceprogram.experiment.ship;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.Cell;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.PartBox;
import io.github.jxt159753.blockspaceprogram.experiment.ship.ShipBody.Motion;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.List;

@EventBusSubscriber(modid = "blockspaceprogram")
public final class ShipExperiment {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceKey<Level> VOID = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("blockspaceprogram", "ship_test_void"));
    private static final BlockPos PISTON = new BlockPos(8, 79, 24);
    private static final BlockPos FLOOR = new BlockPos(12, 80, 24);
    private static final double SURFACE_Y = 81;
    private static final int LIFT_HEIGHT = 8;
    private static final double PART_HALF_HEIGHT = 0.25;
    private static final double CONTACT_EPSILON = 0.025;
    private static final double HANDOFF_EPSILON = 1e-4;
    private static final PartBox SMALL = new PartBox(new BlockPos(1, 1, 1), new BlockPos(3, 3, 3), 2);
    private static final PartBox LEFT = new PartBox(new BlockPos(-4, 1, 0), new BlockPos(2, 3, 4), 2);
    private static final PartBox RIGHT = new PartBox(new BlockPos(2, 1, 0), new BlockPos(8, 3, 4), 6);

    private static ShipExperiment active;

    private ShipBody ground;
    private ShipBody platform;
    private ShipBody free;
    private Motion handoff;
    private double platformStartY;
    private double midOffset;
    private double midY;
    private int elapsed;

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bspship").requires(source -> source.hasPermission(2))
                .then(Commands.literal("verify").executes(context -> start(context.getSource()))));
    }

    private static int start(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        ServerLevel space = source.getServer().getLevel(VOID);
        if (active != null || !ModList.get().isLoaded("create") || space == null
                || !level.getBlockState(FLOOR).is(Blocks.STONE)
                || !level.getBlockState(PISTON.above()).is(Blocks.STONE)
                || !level.isEmptyBlock(PISTON.east()) || !space.hasChunk(0, 1)) {
            source.sendFailure(Component.literal("Prepare the idle Create lift, stone floor and forced chunks "
                    + "as described in the ship rigid body experiment."));
            return 0;
        }
        active = new ShipExperiment();
        try {
            active.ground = ShipBody.spawn(level, "ship-test-ground", List.of(SMALL),
                    new Vector3d(12, 88, 24), new Quaterniond());
            active.platform = ShipBody.spawn(level, "ship-test-lift", List.of(SMALL),
                    new Vector3d(8, 88, 24), new Quaterniond());
            active.free = ShipBody.spawn(space, "ship-test-void", List.of(LEFT, RIGHT),
                    new Vector3d(8, 88, 24), new Quaterniond());
            active.checkCells();
            return 1;
        } catch (RuntimeException failure) {
            LOG.error("SHIP FAIL during setup", failure);
            finish();
            return 0;
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (active == null) return;
        try {
            if (active.advance()) finish();
        } catch (RuntimeException failure) {
            LOG.error("SHIP FAIL at tick {}", active.elapsed, failure);
            finish();
        }
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        finish();
    }

    private static void finish() {
        if (active == null) return;
        if (active.ground != null) active.ground.remove();
        if (active.platform != null) active.platform.remove();
        if (active.free != null) active.free.remove();
        active = null;
    }

    private void checkCells() {
        var state = ground.subLevel().getPlot().getEmbeddedLevelAccessor().getBlockState(BlockPos.ZERO);
        var shape = state.getCollisionShape(ground.subLevel().getLevel(), ground.plotOrigin());
        var collisionCell = BlockSpaceProgram.SHIP_COLLISION_CELL.get();
        int states = collisionCell.getStateDefinition().getPossibleStates().size();
        check(states == 1000
                && state.is(collisionCell) && state.getRenderShape() == RenderShape.INVISIBLE
                && shape.bounds().equals(new AABB(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)), "collision cell state");

        var cells = ShipCollisionLayout.rasterize(List.of(LEFT,
                new PartBox(new BlockPos(2, 1, 0), new BlockPos(8, 2, 4), 6)));
        check(cells.size() == 3 && cells.get(new BlockPos(-1, 0, 0)).mass() == 1
                && cells.get(BlockPos.ZERO).equals(new Cell(new BlockPos(0, 1, 0), new BlockPos(4, 3, 4), 4)),
                "negative cell coordinate or shared cell bounds/mass");
        LOG.info("SHIP PASS allocation and quarter cells states={} shape={}", states, shape.bounds());
    }

    private boolean advance() {
        elapsed++;
        if (elapsed == 10) {
            double groundDrop = 88.5 - ground.motion().pose().position().y();
            double spaceDrop = 88.5 - free.motion().pose().position().y();
            check(groundDrop > 0.5, "Overworld body did not fall");
            near(spaceDrop, 0, HANDOFF_EPSILON, "zero-gravity drop");
            LOG.info("SHIP PASS dimension gravity tick={} overworldDrop={} voidDrop={}", elapsed, groundDrop, spaceDrop);
        }
        if (elapsed == 80) {
            double floorY = settledHeight(ground, SURFACE_Y, "terrain");
            platformStartY = settledHeight(platform, SURFACE_Y, "initial platform");
            LOG.info("SHIP PASS terrain surfaceY={} centerY={}", SURFACE_Y, floorY);
            checkMassAndStartMotion();
            ServerLevel level = platform.subLevel().getLevel();
            var server = level.getServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level)
                    .withSuppressedOutput(), "setblock 9 79 24 create:creative_motor[facing=west]");
        }
        if (elapsed == 100) rebuild();
        if (elapsed == 101) checkNextTick();
        if (elapsed > 80 && midOffset == 0) sampleMovingPlatform();
        if (elapsed < 420) return false;

        check(midOffset > 0 && platform.subLevel().getLevel().getBlockState(PISTON.above(1 + LIFT_HEIGHT))
                .is(Blocks.STONE), "Create lift did not extend through its full stroke");
        double finalY = settledHeight(platform, SURFACE_Y + LIFT_HEIGHT, "raised platform");
        near(finalY - platformStartY, LIFT_HEIGHT, CONTACT_EPSILON, "Create lift displacement");
        LOG.info("SHIP PASS Create lift initialY={} movingOffset={} movingY={} finalY={} rise={}",
                platformStartY, midOffset, midY, finalY, finalY - platformStartY);
        LOG.info("SHIP PASS all checks");
        return true;
    }

    private void checkMassAndStartMotion() {
        double expectedMass = 8;
        Vector3d expectedInertia = new Vector3d(5.0 / 6, 29.0 / 6, 13.0 / 3);
        var properties = free.subLevel().getMassTracker();
        BlockPos origin = free.plotOrigin();
        Vector3d localCenter = new Vector3d(properties.getCenterOfMass()).sub(origin.getX(), origin.getY(), origin.getZ());
        var tensor = properties.getInertiaTensor();
        Vector3d inertia = new Vector3d(tensor.m00(), tensor.m11(), tensor.m22());
        near(properties.getMass(), expectedMass, HANDOFF_EPSILON, "cell mass");
        near(localCenter.distance(0.75, 0.5, 0.5), 0, HANDOFF_EPSILON, "cell center of mass");
        near(inertia.distance(expectedInertia), 0, HANDOFF_EPSILON, "cell inertia");
        near(free.motion().pose().position().distance(8.75, 88.5, 24.5), 0, HANDOFF_EPSILON, "void drift at tick 80");

        var pipeline = free.pipeline();
        var body = free.subLevel();
        var expectedCenter = new Vector3d(origin.getX() + 0.75, origin.getY() + 0.5, origin.getZ() + 0.5);
        pipeline.applyImpulse(body, expectedCenter, new Vector3d(0, 4, 0));
        Motion motion = free.motion();
        near(motion.linear().distance(0, 0.5, 0), 0, HANDOFF_EPSILON, "native linear impulse");
        near(motion.angular().length(), 0, HANDOFF_EPSILON, "native impulse at center of mass");
        Vector3d expectedSpin = new Vector3d(0.2, 0.7, 0.3);
        pipeline.applyLinearAndAngularImpulse(body, new Vector3d(), expectedSpin.mul(expectedInertia, new Vector3d()), true);
        Vector3d spin = free.motion().angular();
        near(spin.distance(expectedSpin), 0, HANDOFF_EPSILON, "native angular impulse");
        LOG.info("SHIP PASS distributed mass mass={} localCenter={} inertia={} deltaVy={} deltaOmega={}",
                properties.getMass(), localCenter, inertia, motion.linear().y(), spin);
        // Leave a pure yaw so the next-tick orientation after rebuilding has an analytic reference.
        pipeline.addLinearAndAngularVelocity(body, new Vector3d(), new Vector3d(-spin.x, 0, -spin.z));
    }

    private void rebuild() {
        ShipBody old = free;
        Motion before = old.motion();
        Vector3d oldAnchor = old.pointInWorld(before.pose(), new Vector3d());
        free = old.rebuild(List.of(LEFT), before);
        handoff = free.motion();
        Vector3d newAnchor = free.pointInWorld(handoff.pose(), new Vector3d());

        check(old.subLevel().isRemoved() && !old.subLevel().getUniqueId().equals(free.subLevel().getUniqueId()),
                "old body must be replaced");
        double shift = before.pose().position().distance(handoff.pose().position());
        near(shift, 0.875, HANDOFF_EPSILON, "nonzero center of mass shift");
        double positionError = oldAnchor.distance(newAnchor);
        double rotationError = angleBetween(before.pose().orientation(), handoff.pose().orientation());
        double velocityError = before.velocityAt(oldAnchor).distance(handoff.velocityAt(newAnchor));
        double spinError = before.angular().distance(handoff.angular());
        near(positionError, 0, HANDOFF_EPSILON, "rebuild anchor position");
        near(rotationError, 0, HANDOFF_EPSILON, "rebuild orientation");
        near(velocityError, 0, HANDOFF_EPSILON, "rebuild anchor velocity");
        near(spinError, 0, HANDOFF_EPSILON, "rebuild angular velocity");
        LOG.info("SHIP PASS rebuild centerShift={} positionError={} rotationError={} velocityError={} spinError={}",
                shift, positionError, rotationError, velocityError, spinError);
    }

    private void checkNextTick() {
        Motion after = free.motion();
        double delta = 1.0 / 20;
        Vector3d predicted = new Vector3d(handoff.pose().position()).fma(delta, handoff.linear());
        var orientation = new Quaterniond(handoff.pose().orientation()).rotateY(delta * handoff.angular().y());
        double positionError = after.pose().position().distance(predicted);
        double rotationError = angleBetween(orientation, after.pose().orientation());
        near(positionError, 0, HANDOFF_EPSILON, "rebuild next tick position");
        near(rotationError, 0, HANDOFF_EPSILON, "rebuild next tick rotation");
        LOG.info("SHIP PASS rebuild next tick positionError={} rotationError={}", positionError, rotationError);
    }

    private void sampleMovingPlatform() {
        var level = platform.subLevel().getLevel();
        var piston = level.getBlockEntity(PISTON);
        if (piston == null) return;
        var data = piston.saveWithoutMetadata(level.registryAccess());
        double offset = data.getFloat("Offset");
        if (data.getBoolean("Running") && offset >= 4 && offset < 5) {
            midOffset = offset;
            midY = platform.motion().pose().position().y();
            near(midY, SURFACE_Y + offset + PART_HALF_HEIGHT, 0.15, "moving Create platform contact");
        }
    }

    private static double settledHeight(ShipBody ship, double surface, String label) {
        Motion motion = ship.motion();
        double centerY = motion.pose().position().y();
        near(centerY, surface + PART_HALF_HEIGHT, CONTACT_EPSILON, label + " center height");
        near(motion.linear().length(), 0, 0.03, label + " settling speed");
        return centerY;
    }

    private static double angleBetween(Quaterniondc before, Quaterniondc after) {
        Quaterniond change = before.difference(after, new Quaterniond());
        return 2 * Math.atan2(new Vector3d(change.x, change.y, change.z).length(), Math.abs(change.w));
    }

    private static void near(double actual, double expected, double tolerance, String label) {
        check(Math.abs(actual - expected) <= tolerance, label + " expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException(label);
    }
}
