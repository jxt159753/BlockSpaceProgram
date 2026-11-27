package io.github.jxt159753.blockspaceprogram.assembly;

import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Ray springs act just beyond the solid feet, which remain part of the Sable hull. */
final class ShipLandingGear {
    private static final double TRAVEL = 0.75;
    private static final double CONTACT_MARGIN = 0.05;
    private static final double WEIGHT_FRACTION = 0.65;
    private static final double DAMPING_RATE = 8;
    private static final double SETTLE_SPEED = 0.4;

    private final List<Leg> legs = new ArrayList<>();
    private final long legParts;

    ShipLandingGear(ShipDesign design) {
        long parts = 0;
        for (int i = 0; i < design.nodes().size(); i++) {
            var node = design.nodes().get(i);
            if (node.part() != null && node.part().types().contains(PartType.LANDING_LEG)) {
                var size = node.part().geometry().size();
                var foot = node.point(new Vec3(size.x / 2, 0, size.z / 2));
                var up = node.pose().rotation().rotate(Direction.UP).getNormal();
                long bit = 1L << i;
                legs.add(new Leg(bit, JOMLConversion.toJOML(foot), new Vector3d(up.getX(), up.getY(), up.getZ()),
                        TRAVEL * node.part().scale()));
                parts |= bit;
            }
        }
        legParts = parts;
    }

    void addImpulse(ShipCollisionBody body, long damagedParts, Vector3dc center, Vector3dc linearVelocity,
            double gravity, double seconds, Vector3d impulse, Vector3d moment) {
        long working = legParts & ~damagedParts;
        if (working == 0) {
            return;
        }
        var subLevel = body.subLevel();
        var angularVelocity = body.pipeline().getAngularVelocity(subLevel, new Vector3d());
        if (linearVelocity.lengthSquared() == 0 && angularVelocity.lengthSquared() == 0) {
            return;
        }

        var pose = subLevel.logicalPose();
        var orientation = pose.orientation();
        var level = subLevel.getLevel();
        var massData = subLevel.getMassTracker();
        double mass = massData.getMass();
        double massPerLeg = mass / Long.bitCount(working);
        Matrix3d inverseInertia = null;
        for (Leg leg : legs) {
            if ((working & leg.part) == 0) {
                continue;
            }
            var lever = new Vector3d(leg.foot).sub(center);
            var worldLever = orientation.transform(lever, new Vector3d());
            Vec3 foot = JOMLConversion.toMojang(worldLever.add(pose.position(), new Vector3d()));
            Vec3 up = JOMLConversion.toMojang(orientation.transform(leg.up, new Vector3d()));
            Vec3 end = foot.add(up.scale(-leg.travel));
            Vec3 start = foot.add(up.scale(CONTACT_MARGIN));
            BlockPos from = BlockPos.containing(start);
            BlockPos to = BlockPos.containing(end);
            if (!level.hasChunksAt(Math.min(from.getX(), to.getX()), Math.min(from.getZ(), to.getZ()),
                    Math.max(from.getX(), to.getX()), Math.max(from.getZ(), to.getZ()))) {
                continue;
            }
            var ray = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
            ((ClipContextExtension) ray).sable$setDoNotProject(true);
            var hit = level.clip(ray);
            Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
            if (hit.getType() != HitResult.Type.BLOCK || hit.isInside() || normal.dot(up) < 0.5) {
                continue;
            }

            double gap = Math.max(0, foot.subtract(hit.getLocation()).dot(up));
            double compression = Math.max(0, 1 - gap / leg.travel);
            double normalSpeed = angularVelocity.cross(worldLever, new Vector3d()).add(linearVelocity)
                    .dot(normal.x, normal.y, normal.z);
            // Fade the spring as motion stops so the solid contacts can settle without bouncing.
            double engagement = Math.min(1, Math.abs(normalSpeed) / SETTLE_SPEED);
            double acceleration = WEIGHT_FRACTION * gravity * compression * engagement - DAMPING_RATE * normalSpeed;
            if (acceleration <= 0) {
                continue;
            }

            if (inverseInertia == null) {
                inverseInertia = new Matrix3d(massData.getInertiaTensor()).invert();
            }
            var localNormal = orientation.transformInverse(JOMLConversion.toJOML(normal));
            var torque = lever.cross(localNormal);
            // A leg far from the center can rotate the hull with less impulse than a centered leg.
            double effectiveMass = 1 / (1 / mass + torque.dot(inverseInertia.transform(torque, new Vector3d())));
            double strength = acceleration * Math.min(massPerLeg, effectiveMass) * seconds;
            impulse.fma(strength, localNormal);
            moment.fma(strength, torque);
        }
    }

    private record Leg(long part, Vector3dc foot, Vector3dc up, double travel) {}
}
