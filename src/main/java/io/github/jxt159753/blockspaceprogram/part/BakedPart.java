package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Snapshot of a module in local block coordinates, with its origin at the minimum occupied corner. */
public record BakedPart(
        List<StructureBlock> blocks,
        BlockPartProperties properties,
        Vec3 centerOfMass,
        InertiaTensor inertia,
        Set<PartType> types) {

    static final int MAX_SIDE = 9;

    public static final Codec<BakedPart> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StructureBlock.CODEC.listOf(1, MAX_SIDE * MAX_SIDE * MAX_SIDE).fieldOf("blocks").forGetter(BakedPart::blocks),
            BlockPartProperties.CODEC.fieldOf("properties").forGetter(BakedPart::properties),
            Vec3.CODEC.fieldOf("center_of_mass").forGetter(BakedPart::centerOfMass),
            InertiaTensor.CODEC.fieldOf("inertia").forGetter(BakedPart::inertia),
            PartType.CODEC.listOf().xmap(Set::copyOf, types -> types.stream().sorted().toList())
                    .fieldOf("types").forGetter(BakedPart::types)
    ).apply(instance, BakedPart::new));

    public BakedPart {
        blocks = List.copyOf(blocks);
        types = Set.copyOf(types);
    }

    static Optional<BakedPart> bake(Map<BlockPos, BlockState> structure) {
        BlockPos origin = new BlockPos(
                structure.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow(),
                structure.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                structure.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow());
        List<StructureBlock> blocks = structure.entrySet().stream()
                .map(entry -> new StructureBlock(entry.getKey().subtract(origin), entry.getValue()))
                .toList();
        List<BlockPartProperties> contributions = blocks.stream()
                .map(block -> BlockPartProperties.get(block.state())).toList();

        double mass = 0;
        double thrust = 0;
        double vacuumFlow = 0;
        double seaLevelFlow = 0;
        double drag = 0;
        double heat = Double.POSITIVE_INFINITY;
        double electricCapacity = 0;
        Map<ResourceLocation, Double> fuelCapacity = new HashMap<>();
        for (BlockPartProperties contribution : contributions) {
            mass += contribution.mass();
            thrust += contribution.thrust();
            if (contribution.thrust() > 0) {
                vacuumFlow += contribution.thrust() / contribution.vacuumSpecificImpulse();
                seaLevelFlow += contribution.thrust() / contribution.seaLevelSpecificImpulse();
            }
            drag += contribution.dragCoefficient();
            if (contribution.heatResistance() > 0) {
                heat = Math.min(heat, contribution.heatResistance());
            }
            electricCapacity += contribution.electricCapacity();
            contribution.fuelCapacity().forEach((fuel, amount) -> fuelCapacity.merge(fuel, amount, Double::sum));
        }
        double vacuumIsp = vacuumFlow == 0 ? 0 : thrust / vacuumFlow;
        double seaLevelIsp = seaLevelFlow == 0 ? 0 : thrust / seaLevelFlow;
        if (!finite(mass, thrust, vacuumIsp, seaLevelIsp, drag, electricCapacity)
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
                drag, Double.isFinite(heat) ? heat : 0, electricCapacity);
        return Optional.of(new BakedPart(blocks, properties, center, new InertiaTensor(xx, yy, zz, xy, xz, yz),
                PartType.classify(structure.values())));
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
        private static final Codec<StructureBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(StructureBlock::pos),
                BlockState.CODEC.fieldOf("state").forGetter(StructureBlock::state)
        ).apply(instance, StructureBlock::new));

        public StructureBlock {
            pos = pos.immutable();
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
