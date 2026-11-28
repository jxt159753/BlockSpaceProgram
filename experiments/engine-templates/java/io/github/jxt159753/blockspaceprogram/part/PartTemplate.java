package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

record PartTemplate(BlockPos size, List<PartConnector> connectors, PartEngine.Outlet nozzle,
                    BlockPartProperties properties, double electricPower, ResourceLocation displayModel) {
    static final Codec<PartTemplate> CODEC = RecordCodecBuilder.<PartTemplate>create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("size").forGetter(PartTemplate::size),
            PartConnector.CODEC.listOf(0, BakedPart.MAX_BLOCKS).fieldOf("connectors").forGetter(PartTemplate::connectors),
            PartEngine.Outlet.CODEC.fieldOf("nozzle").forGetter(PartTemplate::nozzle),
            BlockPartProperties.CODEC.fieldOf("properties").forGetter(PartTemplate::properties),
            PartEngine.NON_NEGATIVE.optionalFieldOf("electric_power", 0.0).forGetter(PartTemplate::electricPower),
            ResourceLocation.CODEC.fieldOf("display_model").forGetter(PartTemplate::displayModel)
    ).apply(instance, PartTemplate::new)).validate(PartTemplate::validate);

    ItemStack createItem() {
        for (float scale : BakedPart.SCALES) {
            if (connectors.stream().allMatch(connector -> connector.allowsScale(scale))) {
                var center = Vec3.atLowerCornerOf(size).scale(0.5);
                double factor = properties.mass() / 12;
                var inertia = new BakedPart.InertiaTensor(factor * (size.getY() * size.getY() + size.getZ() * size.getZ()),
                        factor * (size.getX() * size.getX() + size.getZ() * size.getZ()),
                        factor * (size.getX() * size.getX() + size.getY() * size.getY()), 0, 0, 0);
                var engine = new PartEngine(nozzle, PartEngine.performance(properties), electricPower);
                var part = new BakedPart(BakedPart.Geometry.box(size), properties, center, inertia,
                        Set.of(PartType.ENGINE), scale, connectors, Optional.of(displayModel), List.of(engine));
                var item = new ItemStack(BlockSpaceProgram.BAKED_PART.get());
                item.set(BlockSpaceProgram.PART_DATA, part);
                return item;
            }
        }
        return ItemStack.EMPTY;
    }

    private DataResult<PartTemplate> validate() {
        if (!validSide(size.getX()) || !validSide(size.getY()) || !validSide(size.getZ())) {
            return DataResult.error(() -> "Template sizes must be in [1, " + BakedPart.MAX_SIDE + "]");
        }
        if (!displayModel.getPath().startsWith("part/")) {
            return DataResult.error(() -> "Template display models must be in models/part/");
        }
        if (properties.thrust() <= 0 || properties.vacuumSpecificImpulse() <= 0 || properties.seaLevelSpecificImpulse() <= 0) {
            return DataResult.error(() -> "Engine templates need positive thrust and specific impulses");
        }
        Vec3 max = Vec3.atLowerCornerOf(size);
        var exhaustFace = new AABB(nozzle.point(), nozzle.point());
        if (!onFace(exhaustFace, nozzle.exhaust(), max)) {
            return DataResult.error(() -> "The nozzle must be on the specified face of the template");
        }
        for (PartConnector connector : connectors) {
            if (!onFace(connector.modelFace(), connector.normal(), max)
                    || connector.kind().isStack() && connector.width() != connector.height()) {
                return DataResult.error(() -> "Each connector must fit on its face and stack connectors must be square");
            }
        }
        return DataResult.success(this);
    }

    private static boolean validSide(int side) {
        return side >= 1 && side <= BakedPart.MAX_SIDE;
    }

    private static boolean onFace(AABB face, Direction normal, Vec3 max) {
        double plane = normal.getAxisDirection() == Direction.AxisDirection.POSITIVE ? max.get(normal.getAxis()) : 0;
        return face.min(normal.getAxis()) == plane && face.max(normal.getAxis()) == plane
                && face.minX >= 0 && face.minY >= 0 && face.minZ >= 0
                && face.maxX <= max.x && face.maxY <= max.y && face.maxZ <= max.z;
    }
}
