package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/** A planar rectangle in unscaled baked model coordinates. Stack connectors are square. */
public record PartConnector(Kind kind, Vec3 center, Direction normal, int width, int height) {
    private static final Codec<Integer> SIDE = Codec.intRange(1, BakedPart.MAX_SIDE);
    static final Codec<PartConnector> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Kind.CODEC.fieldOf("kind").forGetter(PartConnector::kind),
            Vec3.CODEC.fieldOf("center").forGetter(PartConnector::center),
            Direction.CODEC.fieldOf("normal").forGetter(PartConnector::normal),
            SIDE.fieldOf("width").forGetter(PartConnector::width),
            SIDE.fieldOf("height").forGetter(PartConnector::height)
    ).apply(instance, PartConnector::new));
    static final StreamCodec<FriendlyByteBuf, PartConnector> STREAM_CODEC = StreamCodec.of((buffer, connector) -> {
        buffer.writeEnum(connector.kind());
        buffer.writeVec3(connector.center());
        buffer.writeEnum(connector.normal());
        buffer.writeByte(connector.width());
        buffer.writeByte(connector.height());
    }, buffer -> new PartConnector(buffer.readEnum(Kind.class), buffer.readVec3(),
            buffer.readEnum(Direction.class), buffer.readUnsignedByte(), buffer.readUnsignedByte()));

    private static final Codec<List<Integer>> SIZE_CODEC = Codec.intRange(1, BakedPart.MAX_SIDE * 4)
            .listOf(1, BakedPart.MAX_SIDE * 4);
    private static final List<Integer> DEFAULT_SIZES = IntStream.rangeClosed(1, BakedPart.MAX_SIDE * 4).boxed().toList();
    private static final DataMapType<Block, List<Integer>> SIZES = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "connector_sizes"),
            Registries.BLOCK, SIZE_CODEC).synced(SIZE_CODEC, true).build();

    public enum Kind implements StringRepresentable {
        STACK, SEPARATION, DOCKING, SURFACE;

        private static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        public boolean isStack() {
            return this != SURFACE;
        }

        public boolean separable() {
            return this == SEPARATION || this == DOCKING;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(SIZES);
    }

    public int sizeInQuarters(float scale) {
        return Math.round(width * scale * 4);
    }

    public boolean allowsScale(float scale) {
        return !kind.isStack() || Objects.requireNonNullElse(
                BlockSpaceProgram.STACK_INTERFACE.get().builtInRegistryHolder().getData(SIZES), DEFAULT_SIZES)
                .contains(sizeInQuarters(scale));
    }

    public AABB modelFace() {
        Direction u = tangent(normal);
        Direction v = secondTangent(normal);
        Vec3 half = Vec3.atLowerCornerOf(u.getNormal()).scale(width / 2.0)
                .add(Vec3.atLowerCornerOf(v.getNormal()).scale(height / 2.0));
        return new AABB(center.subtract(half), center.add(half));
    }

    public static Direction tangent(Direction normal) {
        return normal.getAxis() == Direction.Axis.X ? Direction.UP : Direction.EAST;
    }

    private static Direction secondTangent(Direction normal) {
        return normal.getAxis() == Direction.Axis.Z ? Direction.UP : Direction.SOUTH;
    }

    static List<PartConnector> scan(Map<BlockPos, BlockState> structure, BlockPos origin) {
        Set<BlockPos> seen = new HashSet<>();
        List<PartConnector> connectors = new ArrayList<>();
        for (BlockPos start : structure.keySet().stream().sorted().toList()) {
            BlockState state = structure.get(start);
            if (!(state.getBlock() instanceof PartConnectorBlock marker) || !seen.add(start)) {
                continue;
            }
            Direction normal = state.getValue(BlockStateProperties.FACING);
            Direction u = tangent(normal);
            Direction v = secondTangent(normal);
            int minU = u.getAxis().choose(start.getX(), start.getY(), start.getZ());
            int minV = v.getAxis().choose(start.getX(), start.getY(), start.getZ());
            int maxU = minU;
            int maxV = minV;
            int count = 0;
            ArrayDeque<BlockPos> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                BlockPos pos = pending.remove();
                for (int distance = 1; distance < BakedPart.MAX_SIDE; distance++) {
                    if (structure.containsKey(pos.relative(normal, distance))) {
                        throw new InvalidConnector(pos, "blocked");
                    }
                }
                int atU = u.getAxis().choose(pos.getX(), pos.getY(), pos.getZ());
                int atV = v.getAxis().choose(pos.getX(), pos.getY(), pos.getZ());
                minU = Math.min(minU, atU);
                minV = Math.min(minV, atV);
                maxU = Math.max(maxU, atU);
                maxV = Math.max(maxV, atV);
                count++;
                for (Direction step : List.of(u, u.getOpposite(), v, v.getOpposite())) {
                    BlockPos adjacent = pos.relative(step);
                    BlockState other = structure.get(adjacent);
                    if (other != null && other.getBlock() instanceof PartConnectorBlock next
                            && other.getValue(BlockStateProperties.FACING) == normal) {
                        if (next.kind() != marker.kind()) {
                            throw new InvalidConnector(adjacent, "mixed");
                        }
                        if (seen.add(adjacent)) {
                            pending.add(adjacent);
                        }
                    }
                }
            }
            int width = maxU - minU + 1;
            int height = maxV - minV + 1;
            if (count != width * height || marker.kind().isStack() && width != height) {
                throw new InvalidConnector(start, "shape");
            }
            double offsetU = (minU + maxU) / 2.0 - u.getAxis().choose(start.getX(), start.getY(), start.getZ());
            double offsetV = (minV + maxV) / 2.0 - v.getAxis().choose(start.getX(), start.getY(), start.getZ());
            Vec3 center = Vec3.atCenterOf(start.subtract(origin))
                    .add(Vec3.atLowerCornerOf(normal.getNormal()).scale(0.5))
                    .add(Vec3.atLowerCornerOf(u.getNormal()).scale(offsetU))
                    .add(Vec3.atLowerCornerOf(v.getNormal()).scale(offsetV));
            connectors.add(new PartConnector(marker.kind(), center, normal, width, height));
        }
        return List.copyOf(connectors);
    }

    static final class InvalidConnector extends IllegalArgumentException {
        final BlockPos pos;
        final String reason;

        InvalidConnector(BlockPos pos, String reason) {
            this.pos = pos;
            this.reason = reason;
        }
    }
}
