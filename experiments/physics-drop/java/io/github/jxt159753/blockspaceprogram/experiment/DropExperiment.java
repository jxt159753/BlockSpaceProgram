package io.github.jxt159753.blockspaceprogram.experiment;

import com.mojang.logging.LogUtils;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Locale;

@EventBusSubscriber(modid = "blockspaceprogram")
public final class DropExperiment {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int HEIGHT = 32;
    private static final int CYCLE = 100;
    private static final int DROPS = 4;
    private static final double CONTACT_TOLERANCE = 0.02;
    private static DropExperiment active;

    private enum Mode { IDLE, SABLE, SINGLE }

    private final Mode mode;
    private final ServerLevel level;
    private final Vec3 start;
    private DropBox box;
    private Display.BlockDisplay marker;
    private int elapsed;
    private int contact;
    private double lowest;
    private double rebound;
    private double tailLow;
    private double tailHigh;

    private DropExperiment(Mode mode, ServerLevel level, BlockPos base) {
        this.mode = mode;
        this.level = level;
        this.start = Vec3.atCenterOf(base.above(HEIGHT));
        if (mode != Mode.IDLE && !level.players().isEmpty()) {
            marker = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
            var tag = new CompoundTag();
            tag.put(Display.BlockDisplay.TAG_BLOCK_STATE, NbtUtils.writeBlockState(Blocks.COPPER_BLOCK.defaultBlockState()));
            tag.putInt(Display.TAG_POS_ROT_INTERPOLATION_DURATION, 1);
            // The display's origin can briefly enter the stone while the solver corrects contact.
            tag.put(Display.TAG_BRIGHTNESS, Brightness.CODEC.encodeStart(NbtOps.INSTANCE, Brightness.FULL_BRIGHT).getOrThrow());
            marker.load(tag);
            positionMarker(start);
            level.addFreshEntity(marker);
        }
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var command = Commands.literal("bspdrop").requires(source -> source.hasPermission(2));
        for (Mode mode : Mode.values()) {
            command.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT))
                    .executes(ctx -> start(ctx.getSource(), mode)));
        }
        event.getDispatcher().register(command);
    }

    private static int start(CommandSourceStack source, Mode mode) {
        ServerLevel level = source.getLevel();
        BlockPos base = BlockPos.containing(source.getPosition());
        if (active != null) {
            source.sendFailure(Component.literal("A drop trial is already running."));
            return 0;
        }
        if (level.dimension() != Level.OVERWORLD ||
                !level.getBlockState(base.below()).isFaceSturdy(level, base.below(), Direction.UP)) {
            source.sendFailure(Component.literal("Start one block above a solid floor in the Overworld."));
            return 0;
        }
        active = new DropExperiment(mode, level, base);
        source.sendSuccess(() -> Component.literal("Starting physics drop: " + mode), false);
        return 1;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (active != null && active.advance()) finish();
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        finish();
    }

    private static void finish() {
        if (active == null) return;
        if (active.box != null) active.box.remove();
        if (active.marker != null) active.marker.discard();
        active = null;
    }

    private boolean advance() {
        int tick = elapsed % CYCLE;
        if (box != null) {
            Vec3 center = box.tick();
            if (center == null) {
                LOG.error("DROP invalid: Sable unloaded the box. Keep the test chunk force-loaded.");
                return true;
            }
            int age = tick == 0 ? CYCLE : tick;
            double height = center.y - (start.y - HEIGHT);
            observe(age, height);
            positionMarker(center);
            if (age == CYCLE) {
                LOG.info("DROP landing {} cycle={} contactTick={} minHeight={} rebound={} tailRange={} finalHeight={}",
                        mode, elapsed / CYCLE, contact, lowest, rebound, tailHigh - tailLow, height);
                if (contact < 0 || !(Math.abs(height) <= CONTACT_TOLERANCE)) {
                    LOG.error("DROP invalid: the box did not finish on the platform.");
                    return true;
                }
                box.remove();
                box = null;
            }
        }
        if (elapsed == CYCLE * DROPS) {
            var mspt = SparkProvider.get().mspt().poll(StatisticWindow.MillisPerTick.SECONDS_10);
            LOG.info("DROP spark {} window=10s mean={} p50={} p95={}", mode,
                    mspt.mean(), mspt.median(), mspt.percentile95th());
            return true;
        }
        if (tick == 0 && mode != Mode.IDLE) beginDrop();
        elapsed++;
        return false;
    }

    private void beginDrop() {
        contact = -1;
        lowest = tailLow = Double.POSITIVE_INFINITY;
        tailHigh = Double.NEGATIVE_INFINITY;
        rebound = 0;
        box = mode == Mode.SABLE ? new DropBox.Sable(level, start) : new DropBox.Single(level, start);
        positionMarker(start);
    }

    private void positionMarker(Vec3 center) {
        if (marker != null) marker.setPos(center.x - 0.5, center.y - 0.5, center.z - 0.5);
    }

    private void observe(int age, double height) {
        lowest = Math.min(lowest, height);
        if (contact < 0 && height <= CONTACT_TOLERANCE) contact = age;
        if (contact >= 0) rebound = Math.max(rebound, height - lowest);
        if (age > CYCLE - 20) {
            tailLow = Math.min(tailLow, height);
            tailHigh = Math.max(tailHigh, height);
        }
    }
}
