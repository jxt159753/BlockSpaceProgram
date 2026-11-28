package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Grid;
import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Side;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Compares live rasterization with a hypothetical compact, baked part cache. */
final class AeroCacheTrial {
    private static final String CACHE_KEY = "aero_projection";
    private static final Side[] SIDES = Side.values();

    private AeroCacheTrial() {}

    static void run(Logger logger, ShipDesign benchmark) throws IOException {
        var blocks = AeroTrialDesigns.cube(9, Blocks.IRON_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(0, 8, 4), Blocks.SPRUCE_PLANKS.defaultBlockState());
        var part = AeroTrialDesigns.bake(blocks);
        var design = new ShipDesign(List.of(new ShipDesign.Node(part, PartPose.ROOT, -1)), List.of(), 1);
        compare(logger, "dense9", design);
        var rotated = new PartPose(new BlockPos(-3, 5, 2),
                PartPose.align(Direction.UP, Direction.EAST, Direction.NORTH, Direction.WEST));
        var scaled = new ShipDesign(List.of(new ShipDesign.Node(part.withScale(0.5F), rotated, -1)), List.of(), 1);
        checkEquivalent(scaled, withCache(scaled, encode(scaled)));
        compare(logger, "63-nodes", benchmark);
    }

    private static void compare(Logger logger, String name, ShipDesign design) throws IOException {
        CompoundTag live = encode(design);
        CompoundTag baked = withCache(design, live);
        checkEquivalent(design, baked);
        final int samples = 40;
        var liveTimes = new long[samples];
        var cachedTimes = new long[samples];
        for (int i = -15; i < samples; i++) {
            long liveNanos = ShipAerodynamics.compute(design).buildNanos();
            long start = System.nanoTime();
            AeroProjection projection = assembleFromCache(design, baked);
            long cachedNanos = System.nanoTime() - start;
            if (projection.area(Side.POS_Y) <= 0) {
                throw new IllegalStateException("Empty cached silhouette");
            }
            if (i >= 0) {
                liveTimes[i] = liveNanos;
                cachedTimes[i] = cachedNanos;
            }
        }
        logger.info("AERO CACHE fixture={} liveBytes={} cachedBytes={} liveGzip={} cachedGzip={} liveMs={} cachedMs={}",
                name, bytes(live, false), bytes(baked, false), bytes(live, true), bytes(baked, true),
                medianMs(liveTimes), medianMs(cachedTimes));
    }

    private static void checkEquivalent(ShipDesign design, CompoundTag baked) {
        AeroProjection live = ShipAerodynamics.compute(design).projection();
        AeroProjection cached = assembleFromCache(design, baked);
        for (Side side : SIDES) {
            var direction = new Vector3d().setComponent(side.axis, side.sign);
            requireNear(live.area(side), cached.area(side));
            requireNear(live.dragArea(direction), cached.dragArea(direction));
            requireNear(0, live.centerOfPressure(direction, new Vector3d())
                    .distance(cached.centerOfPressure(direction, new Vector3d())));
            for (int i = 0; i < design.nodes().size(); i++) {
                requireNear(live.exposedArea(side, i), cached.exposedArea(side, i));
            }
        }
    }

    private static void requireNear(double expected, double actual) {
        if (Math.abs(expected - actual) > 1e-8) {
            throw new IllegalStateException("Baked projection differs from live projection: " + expected + " vs " + actual);
        }
    }

    private static CompoundTag encode(ShipDesign design) {
        return (CompoundTag) ShipDesign.FLIGHT_CODEC.encodeStart(NbtOps.INSTANCE, design).getOrThrow();
    }

    private static CompoundTag withCache(ShipDesign design, CompoundTag base) {
        var encoded = base.copy();
        var nodes = encoded.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < design.nodes().size(); i++) {
            var part = design.nodes().get(i).part();
            if (part == null) {
                continue;
            }
            var grid = new Grid();
            for (var block : part.blocks()) {
                var pos = block.pos();
                if (!block.state().isAir()) {
                    grid.addBlock(pos.getX(), pos.getY(), pos.getZ(), 1, 0,
                            BlockPartProperties.get(block.state()).dragCoefficient());
                }
            }
            var cache = new ListTag();
            for (Side side : SIDES) {
                // Each pair is a face corner in baked model coordinates and the saved coefficient.
                var entries = new long[grid.faceCount(side) * 2];
                var cursor = new int[1];
                grid.forEachFace(side, (u, v, face) -> {
                    entries[cursor[0]++] = faceCorner(side, face.depth(), u, v).asLong();
                    entries[cursor[0]++] = Double.doubleToLongBits(face.coefficient());
                });
                cache.add(new LongArrayTag(entries));
            }
            nodes.getCompound(i).getCompound("part").put(CACHE_KEY, cache);
        }
        return encoded;
    }

    private static AeroProjection assembleFromCache(ShipDesign design, CompoundTag encoded) {
        var grid = new Grid();
        var nodes = encoded.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < design.nodes().size(); i++) {
            var node = design.nodes().get(i);
            if (node.hull() != null) {
                ShipAerodynamics.addHull(grid, node, i);
                continue;
            }
            var cache = nodes.getCompound(i).getCompound("part").getList(CACHE_KEY, Tag.TAG_LONG_ARRAY);
            int width = Math.round(node.part().scale() * 4);
            for (Side local : SIDES) {
                Direction.AxisDirection sign = local.sign > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE;
                var normal = node.pose().rotation().rotate(Direction.fromAxisAndDirection(Direction.Axis.values()[local.axis], sign));
                Side side = Side.of(normal.getAxis().ordinal(), normal.getAxisDirection().getStep());
                var entries = ((LongArrayTag) cache.get(local.ordinal())).getAsLongArray();
                for (int j = 0; j < entries.length; j += 2) {
                    var corner = BlockPos.of(entries[j]);
                    var opposite = faceCorner(local, local.depth(corner.getX(), corner.getY(), corner.getZ()),
                            local.u(corner.getX(), corner.getY(), corner.getZ()) + 1,
                            local.v(corner.getX(), corner.getY(), corner.getZ()) + 1);
                    var a = ShipAerodynamics.shipQuarter(node, corner);
                    var b = ShipAerodynamics.shipQuarter(node, opposite);
                    grid.addFace(side, side.depth(a.getX(), a.getY(), a.getZ()),
                            Math.min(side.u(a.getX(), a.getY(), a.getZ()), side.u(b.getX(), b.getY(), b.getZ())),
                            Math.min(side.v(a.getX(), a.getY(), a.getZ()), side.v(b.getX(), b.getY(), b.getZ())),
                            width, i, Double.longBitsToDouble(entries[j + 1]));
                }
            }
        }
        return grid.summarize(design.nodes().size());
    }

    private static BlockPos faceCorner(Side side, int depth, int u, int v) {
        return switch (side.axis) {
            case 0 -> new BlockPos(depth, u, v);
            case 1 -> new BlockPos(v, depth, u);
            default -> new BlockPos(u, v, depth);
        };
    }

    private static int bytes(CompoundTag root, boolean gzip) throws IOException {
        var output = new ByteArrayOutputStream();
        if (gzip) {
            NbtIo.writeCompressed(root, output);
        } else {
            NbtIo.write(root, new DataOutputStream(output));
        }
        return output.size();
    }

    static double medianMs(long[] times) {
        Arrays.sort(times);
        int middle = times.length / 2;
        return (times[middle - 1] + times[middle]) / 2_000_000.0;
    }
}
