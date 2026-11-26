package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The baked minimum corner and a proper right-angle rotation, in ship coordinates. */
public record PartPose(BlockPos origin, OctahedralGroup rotation) {
    private static final int MAX_OFFSET = ShipDesign.MAX_PARTS * 9 * 4;
    private static final List<OctahedralGroup> ROTATIONS = Arrays.stream(OctahedralGroup.values())
            .filter(ShipCollisionLayout::isRotation).toList();
    private static final Codec<OctahedralGroup> ROTATION_CODEC = StringRepresentable.fromEnum(OctahedralGroup::values)
            .validate(rotation -> ROTATIONS.contains(rotation) ? DataResult.success(rotation)
                    : DataResult.error(() -> "Reflected part pose"));
    static final Codec<PartPose> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("origin_quarters").forGetter(PartPose::origin),
            ROTATION_CODEC.fieldOf("rotation").forGetter(PartPose::rotation)
    ).apply(instance, PartPose::new));
    static final StreamCodec<FriendlyByteBuf, PartPose> STREAM_CODEC = StreamCodec.of((buffer, pose) -> {
        buffer.writeBlockPos(pose.origin());
        buffer.writeEnum(pose.rotation());
    }, buffer -> new PartPose(buffer.readBlockPos(), buffer.readEnum(OctahedralGroup.class)));

    public static final PartPose ROOT = new PartPose(BlockPos.ZERO, OctahedralGroup.IDENTITY);

    public PartPose {
        origin = origin.immutable();
    }

    boolean valid() {
        return Math.abs((long) origin.getX()) <= MAX_OFFSET && Math.abs((long) origin.getY()) <= MAX_OFFSET
                && Math.abs((long) origin.getZ()) <= MAX_OFFSET && ROTATIONS.contains(rotation);
    }

    public Vec3 position() {
        return fromQuarters(origin);
    }

    public Vec3 point(Vec3 local) {
        return position().add(rotate(rotation, local));
    }

    Vec3 localPoint(Vec3 point) {
        Vec3 delta = point.subtract(position());
        return new Vec3(delta.dot(Vec3.atLowerCornerOf(rotation.rotate(Direction.EAST).getNormal())),
                delta.dot(Vec3.atLowerCornerOf(rotation.rotate(Direction.UP).getNormal())),
                delta.dot(Vec3.atLowerCornerOf(rotation.rotate(Direction.SOUTH).getNormal())));
    }

    Direction localDirection(Direction direction) {
        for (Direction local : Direction.values()) {
            if (rotation.rotate(local) == direction) {
                return local;
            }
        }
        throw new IllegalArgumentException("Unknown direction " + direction);
    }

    public AABB bounds(BakedPart part) {
        var box = ShipCollisionLayout.PartBox.fromPart(part, origin, rotation);
        return new AABB(fromQuarters(box.min()), fromQuarters(box.max()));
    }

    static Vec3 rotate(OctahedralGroup rotation, Vec3 point) {
        return Vec3.atLowerCornerOf(rotation.rotate(Direction.EAST).getNormal()).scale(point.x)
                .add(Vec3.atLowerCornerOf(rotation.rotate(Direction.UP).getNormal()).scale(point.y))
                .add(Vec3.atLowerCornerOf(rotation.rotate(Direction.SOUTH).getNormal()).scale(point.z));
    }

    static OctahedralGroup align(Direction normal, Direction tangent, Direction targetNormal, Direction targetTangent) {
        for (OctahedralGroup rotation : ROTATIONS) {
            if (rotation.rotate(normal) == targetNormal && rotation.rotate(tangent) == targetTangent) {
                return rotation;
            }
        }
        throw new IllegalArgumentException("Normal and tangent must be perpendicular");
    }

    static BlockPos snap(Vec3 point) {
        return new BlockPos((int) Math.round(point.x * 4), (int) Math.round(point.y * 4), (int) Math.round(point.z * 4));
    }

    public static Vec3 fromQuarters(BlockPos pos) {
        return Vec3.atLowerCornerOf(pos).scale(0.25);
    }
}
