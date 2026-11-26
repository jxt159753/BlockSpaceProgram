package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.logging.LogUtils;
import com.mojang.math.OctahedralGroup;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.PartBox;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class PartCollisionVerification {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int TRIAL_TICKS = 60;
    private static PartCollisionVerification active;

    private final ServerLevel level;
    private final BakedPart part;
    private ShipCollisionBody body;
    private PlacedPartEntity model;
    private double originalMass;
    private int ticks;
    private boolean scaledTrial;

    private PartCollisionVerification(ServerLevel level) {
        this.level = level;
        var iron = Blocks.IRON_BLOCK.defaultBlockState();
        part = BakedPart.bake(Map.of(BlockPos.ZERO, iron, new BlockPos(1, 0, 0), iron,
                new BlockPos(2, 0, 0), iron, new BlockPos(3, 0, 0), iron)).orElseThrow();
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        if (!ModList.get().isLoaded("sable")) {
            return;
        }
        event.getDispatcher().register(Commands.literal("bsppartcollision").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    ServerLevel level = context.getSource().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "ship_test_void")));
                    if (active != null || level == null || !level.hasChunk(0, 1) || !hasChannel(level)) {
                        context.getSource().sendFailure(Component.literal("Load the test chunk and build the channel in the ship test dimension."));
                        return 0;
                    }
                    active = new PartCollisionVerification(level);
                    try {
                        active.startTrial(active.part);
                        return 1;
                    } catch (RuntimeException error) {
                        LOG.error("PART COLLISION FAIL during setup", error);
                        finish();
                        return 0;
                    }
                }));
    }

    private static boolean hasChannel(ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(7, 79, 24, 9, 82, 27)) {
            var state = level.getBlockState(pos);
            if (pos.getX() == 8 ? !state.isAir() : !state.is(Blocks.STONE)) {
                return false;
            }
        }
        return true;
    }

    private void startTrial(BakedPart sample) {
        PartBox box = PartBox.fromPart(sample, BlockPos.ZERO, OctahedralGroup.IDENTITY);
        var cells = ShipCollisionLayout.rasterize(List.of(box));
        double expectedWidth = scaledTrial ? 1 : 4;
        check(cells.size() == expectedWidth && sample.modelToLocal(sample.geometry().size()).x == expectedWidth,
                "Model width or cell count differs from " + expectedWidth);
        if (scaledTrial) {
            var connector = sample.modelToLocal(new Vec3(4, 0.5, 0.5));
            check(connector.equals(new Vec3(1, 0.125, 0.125)), "Connector point differs from the scaled east face");
        }

        var origin = new Vector3d(8.5 - expectedWidth / 2, 80, 20);
        body = ShipCollisionBody.spawn(level, "part-collision-" + sample.scale(), List.of(box), origin, new Quaterniond());
        body.container().addForceLoadTicket(body.subLevel(), SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        var plot = body.subLevel().getPlot().getEmbeddedLevelAccessor();
        double height = scaledTrial ? 0.25 : 1;
        for (var pos : cells.keySet()) {
            var state = plot.getBlockState(pos);
            check(state.is(BlockSpaceProgram.SHIP_COLLISION_CELL.get())
                            && state.getCollisionShape(level, body.plotOrigin().offset(pos)).bounds()
                                    .equals(new AABB(0, 0, 0, 1, height, height)), "Unexpected Sable block shape");
        }
        double mass = body.subLevel().getMassTracker().getMass();
        check(Math.abs(mass - part.properties().mass()) < 1e-6, "Sable mass differs from baked mass");
        if (scaledTrial) {
            check(Math.abs(mass - originalMass) < 1e-6, "Scaling changed Sable mass");
        } else {
            originalMass = mass;
        }
        LOG.info("PART COLLISION width={} cells={} bakedMass={} sableMass={}",
                expectedWidth, cells.size(), part.properties().mass(), mass);

        var stack = new ItemStack(BlockSpaceProgram.BAKED_PART.get());
        stack.set(BlockSpaceProgram.PART_DATA, sample);
        model = new PlacedPartEntity(BlockSpaceProgram.PLACED_PART.get(), level);
        model.setPartStack(stack);
        model.blocksBuilding = false;
        model.setPos(Vec3.atLowerCornerOf(body.plotOrigin()));
        check(level.addFreshEntity(model), "Could not spawn the model preview");
        body.pipeline().addLinearAndAngularVelocity(body.subLevel(), new Vector3d(0, 0, 4), new Vector3d());
        ticks = 0;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (active == null || ++active.ticks < TRIAL_TICKS) {
            return;
        }
        try {
            active.checkPassage();
        } catch (RuntimeException error) {
            LOG.error("PART COLLISION FAIL", error);
            finish();
        }
    }

    private void checkPassage() {
        var pose = body.pipeline().readPose(body.subLevel(), new Pose3d(body.subLevel().logicalPose()));
        Vec3 anchor = pose.transformPosition(Vec3.atLowerCornerOf(body.plotOrigin()));
        if (scaledTrial) {
            check(anchor.z > 28 && Math.abs(anchor.x - 8) < 0.01, "Quarter-scale part did not pass the one-block opening");
            LOG.info("PART COLLISION PASS one-block opening finalOrigin={}", anchor);
            finish();
        } else {
            check(anchor.z > 22 && anchor.z < 24, "Full-size control did not stop at the opening");
            LOG.info("PART COLLISION PASS full-size control stopped at {}", anchor);
            var previousBody = body;
            var previousModel = model;
            body = null;
            model = null;
            scaledTrial = true;
            try {
                startTrial(part.withScale(0.25F));
            } finally {
                // Keep the old plot occupied until the replacement has been initialized.
                previousModel.discard();
                previousBody.remove();
            }
        }
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        finish();
    }

    private static void finish() {
        if (active != null) {
            active.clearTrial();
            active = null;
        }
    }

    private void clearTrial() {
        if (model != null) {
            model.discard();
            model = null;
        }
        if (body != null) {
            body.remove();
            body = null;
        }
    }

    private static void check(boolean success, String message) {
        if (!success) {
            throw new IllegalStateException(message);
        }
    }
}
