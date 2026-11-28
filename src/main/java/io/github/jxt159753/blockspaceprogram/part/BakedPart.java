package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Snapshot of a module in local block coordinates, with its origin at the minimum occupied corner. */
public record BakedPart(
        Geometry geometry,
        BlockPartProperties properties,
        Vec3 centerOfMass,
        InertiaTensor inertia,
        Set<PartType> types,
        float scale,
        List<PartConnector> connectors) {

    public static final int MAX_SIDE = 9;
    public static final int MAX_BLOCKS = MAX_SIDE * MAX_SIDE * MAX_SIDE;
    private static final List<Float> SCALES = List.of(1.0F, 0.5F, 0.25F);
    private static final Codec<Float> SCALE_CODEC = Codec.floatRange(Float.MIN_VALUE, 1.0F)
            .xmap(BakedPart::migrateScale, scale -> scale);

    public static final Codec<BakedPart> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Geometry.CODEC.fieldOf("blocks").forGetter(BakedPart::geometry),
            BlockPartProperties.CODEC.fieldOf("properties").forGetter(BakedPart::properties),
            Vec3.CODEC.fieldOf("center_of_mass").forGetter(BakedPart::centerOfMass),
            InertiaTensor.CODEC.fieldOf("inertia").forGetter(BakedPart::inertia),
            PartType.CODEC.listOf().xmap(Set::copyOf, types -> types.stream().sorted().toList())
                    .fieldOf("types").forGetter(BakedPart::types),
            SCALE_CODEC.optionalFieldOf("scale", 1.0F).forGetter(BakedPart::scale),
            PartConnector.CODEC.listOf(0, MAX_BLOCKS).optionalFieldOf("connectors", List.of()).forGetter(BakedPart::connectors)
    ).apply(instance, BakedPart::new));

    private static final StreamCodec<ByteBuf, BlockPartProperties> PROPERTIES_STREAM =
            ByteBufCodecs.fromCodec(BlockPartProperties.CODEC);
    private static final StreamCodec<FriendlyByteBuf, List<PartConnector>> CONNECTOR_STREAM =
            PartConnector.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_BLOCKS));
    public static final StreamCodec<FriendlyByteBuf, BakedPart> STREAM_CODEC = StreamCodec.of((buffer, part) -> {
        buffer.writeVarInt(part.blocks().size());
        for (StructureBlock block : part.blocks()) {
            BlockPos pos = block.pos();
            buffer.writeShort(pos.getX() | pos.getY() << 4 | pos.getZ() << 8);
            buffer.writeVarInt(Block.getId(block.state()));
        }
        PROPERTIES_STREAM.encode(buffer, part.properties());
        buffer.writeVec3(part.centerOfMass());
        InertiaTensor inertia = part.inertia();
        buffer.writeDouble(inertia.xx()).writeDouble(inertia.yy()).writeDouble(inertia.zz());
        buffer.writeDouble(inertia.xy()).writeDouble(inertia.xz()).writeDouble(inertia.yz());
        var types = EnumSet.noneOf(PartType.class);
        types.addAll(part.types());
        buffer.writeEnumSet(types, PartType.class);
        buffer.writeFloat(part.scale());
        CONNECTOR_STREAM.encode(buffer, part.connectors());
    }, buffer -> {
        int size = buffer.readVarInt();
        if (size < 1 || size > MAX_BLOCKS) {
            throw new DecoderException("Invalid baked geometry size: " + size);
        }
        List<StructureBlock> blocks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int pos = buffer.readUnsignedShort();
            blocks.add(new StructureBlock(new BlockPos(pos & 15, pos >> 4 & 15, pos >> 8 & 15),
                    Block.stateById(buffer.readVarInt())));
        }
        return new BakedPart(new Geometry(blocks), PROPERTIES_STREAM.decode(buffer), buffer.readVec3(),
                new InertiaTensor(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readEnumSet(PartType.class), buffer.readFloat(), CONNECTOR_STREAM.decode(buffer));
    });

    public BakedPart {
        if (!SCALES.contains(scale)) {
            throw new IllegalArgumentException("Part scale must be 1, 1/2 or 1/4");
        }
        types = Set.copyOf(types);
        // Derive the stored size for older items and whenever an item changes scale.
        connectors = connectors.stream().map(connector -> connector.atScale(scale)).toList();
    }

    public BakedPart(Geometry geometry, BlockPartProperties properties, Vec3 centerOfMass, InertiaTensor inertia,
            Set<PartType> types, float scale) {
        this(geometry, properties, centerOfMass, inertia, types, scale, List.of());
    }

    public List<StructureBlock> blocks() {
        return geometry.blocks();
    }

    public BakedPart withScale(float newScale) {
        return new BakedPart(geometry, properties, centerOfMass, inertia, types, newScale, connectors);
    }

    private static float migrateScale(float scale) {
        if (scale == 1.0F) {
            return 1.0F;
        }
        return scale >= 0.5F ? 0.5F : 0.25F;
    }

    public float nextScale() {
        int current = SCALES.indexOf(scale);
        for (int offset = 1; offset < SCALES.size(); offset++) {
            float candidate = SCALES.get((current + offset) % SCALES.size());
            if (connectors.stream().allMatch(connector -> connector.allowsScale(candidate))) {
                return candidate;
            }
        }
        return scale;
    }

    /** Converts a point in the baked model, such as a connector, to scaled local coordinates. */
    public Vec3 modelToLocal(Vec3 point) {
        return point.scale(scale);
    }

    /** The simplified collider, in the same scaled local frame as model points and connectors. */
    public AABB localBounds() {
        return new AABB(Vec3.ZERO, modelToLocal(geometry.size()));
    }

    public static Optional<BakedPart> bake(Map<BlockPos, BlockState> structure) {
        BlockPos origin = new BlockPos(
                structure.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow(),
                structure.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                structure.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow());
        Geometry geometry = new Geometry(structure.entrySet().stream()
                .map(entry -> new StructureBlock(entry.getKey().subtract(origin), entry.getValue()))
                .toList());
        List<StructureBlock> blocks = geometry.blocks();
        List<PartConnector> connectors = PartConnector.scan(structure, origin);
        List<Float> scales = SCALES;
        for (PartConnector connector : connectors) {
            scales = scales.stream().filter(connector::allowsScale).toList();
            if (scales.isEmpty()) {
                BlockPos marker = BlockPos.containing(connector.center().relative(connector.normal(), -0.5));
                throw new PartConnector.InvalidConnector(origin.offset(marker), "size");
            }
        }
        List<BlockPartProperties> contributions = blocks.stream()
                .map(block -> BlockPartProperties.get(block.state())).toList();

        double mass = 0;
        double thrust = 0;
        double vacuumFlow = 0;
        double seaLevelFlow = 0;
        double drag = 0;
        double heat = Double.POSITIVE_INFINITY;
        double electricCapacity = 0;
        double electricPower = 0;
        double impactResistance = Double.POSITIVE_INFINITY;
        Map<ResourceLocation, Double> fuelCapacity = new HashMap<>();
        for (BlockPartProperties contribution : contributions) {
            mass += contribution.mass();
            if (contribution.thrust() > 0) {
                double combinedThrust = thrust + contribution.thrust();
                electricPower = electricPower * (thrust / combinedThrust)
                        + contribution.electricPower() * (contribution.thrust() / combinedThrust);
                thrust = combinedThrust;
                vacuumFlow += contribution.thrust() / contribution.vacuumSpecificImpulse();
                seaLevelFlow += contribution.thrust() / contribution.seaLevelSpecificImpulse();
            }
            drag += contribution.dragCoefficient();
            if (contribution.heatResistance() > 0) {
                heat = Math.min(heat, contribution.heatResistance());
            }
            electricCapacity += contribution.electricCapacity();
            impactResistance = Math.min(impactResistance, contribution.impactResistance());
            contribution.fuelCapacity().forEach((fuel, amount) -> fuelCapacity.merge(fuel, amount, Double::sum));
        }
        double vacuumIsp = vacuumFlow == 0 ? 0 : thrust / vacuumFlow;
        double seaLevelIsp = seaLevelFlow == 0 ? 0 : thrust / seaLevelFlow;
        if (!finite(mass, thrust, vacuumIsp, seaLevelIsp, drag, electricCapacity, electricPower)
                || fuelCapacity.values().stream().anyMatch(value -> !Double.isFinite(value))) {
            return Optional.empty();
        }

        Vec3 center = Vec3.ZERO;
        for (int i = 0; i < blocks.size(); i++) {
            double weight = mass == 0 ? 1.0 / blocks.size() : contributions.get(i).mass() / mass;
            center = center.add(Vec3.atCenterOf(blocks.get(i).pos()).scale(weight));
        }

        double xx = 0;
        double yy = 0;
        double zz = 0;
        double xy = 0;
        double xz = 0;
        double yz = 0;
        for (int i = 0; i < blocks.size(); i++) {
            double blockMass = contributions.get(i).mass();
            Vec3 offset = Vec3.atCenterOf(blocks.get(i).pos()).subtract(center);
            double x = offset.x;
            double y = offset.y;
            double z = offset.z;
            // Each block contributes its unit-cube inertia plus the parallel-axis terms.
            xx += blockMass * (1.0 / 6.0 + y * y + z * z);
            yy += blockMass * (1.0 / 6.0 + x * x + z * z);
            zz += blockMass * (1.0 / 6.0 + x * x + y * y);
            xy -= blockMass * x * y;
            xz -= blockMass * x * z;
            yz -= blockMass * y * z;
        }
        if (!finite(xx, yy, zz, xy, xz, yz)) {
            return Optional.empty();
        }

        BlockPartProperties properties = new BlockPartProperties(mass, fuelCapacity, thrust, vacuumIsp, seaLevelIsp,
                drag, Double.isFinite(heat) ? heat : 0, electricCapacity, electricPower, impactResistance);
        return Optional.of(new BakedPart(geometry, properties, center, new InertiaTensor(xx, yy, zz, xy, xz, yz),
                PartType.classify(structure.values()), scales.getFirst(), connectors));
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    public record StructureBlock(BlockPos pos, BlockState state) {
        private static final Codec<BlockPos> LOCAL_POS_CODEC = BlockPos.CODEC.validate(pos ->
                pos.getX() >= 0 && pos.getX() < MAX_SIDE && pos.getY() >= 0 && pos.getY() < MAX_SIDE
                        && pos.getZ() >= 0 && pos.getZ() < MAX_SIDE
                        ? DataResult.success(pos) : DataResult.error(() -> "Block is outside the part's local bounds: " + pos));
        private static final Codec<StructureBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                LOCAL_POS_CODEC.fieldOf("pos").forGetter(StructureBlock::pos),
                BlockState.CODEC.fieldOf("state").forGetter(StructureBlock::state)
        ).apply(instance, StructureBlock::new));

        public StructureBlock {
            pos = pos.immutable();
        }
    }

    /** The unscaled model is shared by all instances of the same structure. */
    public static final class Geometry {
        private static final Codec<Geometry> CODEC = StructureBlock.CODEC.listOf(1, MAX_BLOCKS)
                .validate(blocks -> blocks.stream().map(StructureBlock::pos).distinct().count() == blocks.size()
                        ? DataResult.success(blocks) : DataResult.error(() -> "Duplicate block positions in part"))
                .xmap(Geometry::new, Geometry::blocks);

        private final List<StructureBlock> blocks;
        private final Vec3 size;
        private final int hash;

        private Geometry(List<StructureBlock> blocks) {
            this.blocks = blocks.stream().sorted(Comparator.comparing(StructureBlock::pos)).toList();
            int width = 0;
            int height = 0;
            int depth = 0;
            for (StructureBlock block : this.blocks) {
                width = Math.max(width, block.pos().getX() + 1);
                height = Math.max(height, block.pos().getY() + 1);
                depth = Math.max(depth, block.pos().getZ() + 1);
            }
            this.size = new Vec3(width, height, depth);
            this.hash = this.blocks.hashCode();
        }

        public List<StructureBlock> blocks() {
            return blocks;
        }

        public Vec3 size() {
            return size;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Geometry geometry && hash == geometry.hash && blocks.equals(geometry.blocks);
        }
    }

    /** Symmetric inertia tensor about the center of mass, in kg·block². */
    public record InertiaTensor(double xx, double yy, double zz, double xy, double xz, double yz) {
        private static final Codec<InertiaTensor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("xx").forGetter(InertiaTensor::xx),
                Codec.DOUBLE.fieldOf("yy").forGetter(InertiaTensor::yy),
                Codec.DOUBLE.fieldOf("zz").forGetter(InertiaTensor::zz),
                Codec.DOUBLE.fieldOf("xy").forGetter(InertiaTensor::xy),
                Codec.DOUBLE.fieldOf("xz").forGetter(InertiaTensor::xz),
                Codec.DOUBLE.fieldOf("yz").forGetter(InertiaTensor::yz)
        ).apply(instance, InertiaTensor::new));
    }
}
