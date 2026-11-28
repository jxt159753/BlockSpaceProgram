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

/** A planar rectangle in unscaled baked model coordinates. Stack connectors are solid or open squares. */
public record PartConnector(Kind kind, Vec3 center, Direction normal, int width, int height, int sizeQuarters) {
    private static final Codec<Integer> SIDE = Codec.intRange(1, BakedPart.MAX_SIDE);
    private static final int MAX_SIZE_QUARTERS = BakedPart.MAX_SIDE * 4;
    private static final Codec<Integer> QUARTER_SIZE = Codec.intRange(1, MAX_SIZE_QUARTERS);
    static final Codec<PartConnector> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Kind.CODEC.fieldOf("kind").forGetter(PartConnector::kind),
            Vec3.CODEC.fieldOf("center").forGetter(PartConnector::center),
            Direction.CODEC.fieldOf("normal").forGetter(PartConnector::normal),
            SIDE.fieldOf("width").forGetter(PartConnector::width),
            SIDE.fieldOf("height").forGetter(PartConnector::height),
            QUARTER_SIZE.optionalFieldOf("size_quarters", 0).forGetter(PartConnector::sizeQuarters)
    ).apply(instance, PartConnector::new));
    static final StreamCodec<FriendlyByteBuf, PartConnector> STREAM_CODEC = StreamCodec.of((buffer, connector) -> {
        buffer.writeEnum(connector.kind());
        buffer.writeVec3(connector.center());
        buffer.writeEnum(connector.normal());
        buffer.writeByte(connector.width());
        buffer.writeByte(connector.height());
    }, buffer -> new PartConnector(buffer.readEnum(Kind.class), buffer.readVec3(),
            buffer.readEnum(Direction.class), buffer.readUnsignedByte(), buffer.readUnsignedByte()));

    private static final Codec<List<Integer>> SIZE_CODEC = QUARTER_SIZE.listOf(1, MAX_SIZE_QUARTERS);
    private static final List<Integer> DEFAULT_SIZES = IntStream.rangeClosed(1, MAX_SIZE_QUARTERS).boxed().toList();
    private static final DataMapType<Block, List<Integer>> SIZES = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "connector_sizes"),
            Registries.BLOCK, SIZE_CODEC).synced(SIZE_CODEC, true).build();

    public PartConnector(Kind kind, Vec3 center, Direction normal, int width, int height) {
        this(kind, center, normal, width, height, kind.isStack() ? width * 4 : 0);
    }

    public enum Kind implements StringRepresentable {
        STACK, SEPARATION, DOCKING, SURFACE;

        private static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        public boolean isStack() {
            return this != SURFACE;
        }

        public boolean separable() {
            return this == SEPARATION || dockable();
        }

        public boolean dockable() {
            return this == DOCKING;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(SIZES);
    }

    private int sizeInQuarters(float scale) {
        return width * (int) (scale * 4);
    }

    PartConnector atScale(float scale) {
        int size = kind.isStack() ? sizeInQuarters(scale) : 0;
        return size == sizeQuarters ? this : new PartConnector(kind, center, normal, width, height, size);
    }

    public boolean matchesStackSize(PartConnector other) {
        return kind.isStack() && other.kind.isStack() && sizeQuarters == other.sizeQuarters;
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
            int startU = u.getAxis().choose(start.getX(), start.getY(), start.getZ());
            int startV = v.getAxis().choose(start.getX(), start.getY(), start.getZ());
            int minU = startU;
            int minV = startV;
            int maxU = minU;
            int maxV = minV;
            int count = 0;
            BlockPos mixed = null;
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
                            if (mixed == null) {
                                mixed = adjacent;
                            }
                        } else if (seen.add(adjacent)) {
                            pending.add(adjacent);
                        }
                    }
                }
            }
            int width = maxU - minU + 1;
            int height = maxV - minV + 1;
            boolean stack = marker.kind().isStack();
            BlockPos corner = start.relative(u, minU - startU).relative(v, minV - startV);
            boolean complete = count == width * height;
            if (stack && width == height && width >= 3 && count == 4 * (width - 1)) {
                // With an empty interior, the perimeter count guarantees every edge position is filled.
                checkOpenInterior(structure, corner, u, v, width);
                complete = true;
            }
            if (mixed != null) {
                throw new InvalidConnector(mixed, "mixed");
            }
            if (!complete || stack && width != height) {
                throw new InvalidConnector(start, "shape");
            }
            Vec3 center = Vec3.atCenterOf(corner.subtract(origin))
                    .add(Vec3.atLowerCornerOf(normal.getNormal()).scale(0.5))
                    .add(Vec3.atLowerCornerOf(u.getNormal()).scale((width - 1) / 2.0))
                    .add(Vec3.atLowerCornerOf(v.getNormal()).scale((height - 1) / 2.0));
            connectors.add(new PartConnector(marker.kind(), center, normal, width, height));
        }
        return List.copyOf(connectors);
    }

    private static void checkOpenInterior(Map<BlockPos, BlockState> structure, BlockPos corner,
            Direction u, Direction v, int width) {
        for (int i = 1; i < width - 1; i++) {
            for (int j = 1; j < width - 1; j++) {
                BlockPos pos = corner.relative(u, i).relative(v, j);
                if (structure.containsKey(pos)) {
                    throw new InvalidConnector(pos, "shape");
                }
            }
        }
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
