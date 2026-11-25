package io.github.jxt159753.blockspaceprogram.experiment;

import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

interface DropBox {
    // Sample the center after one server tick. Sable removes boxes that enter unloaded chunks.
    @Nullable Vec3 tick();

    default void remove() {}

    final class Sable implements DropBox {
        private final SubLevelPhysicsSystem system;
        private final BoxPhysicsObject box;

        Sable(ServerLevel level, Vec3 center) {
            system = SubLevelPhysicsSystem.require(level);
            Pose3d pose = new Pose3d();
            pose.position().set(center.x, center.y, center.z);
            box = new BoxPhysicsObject(pose, new Vector3d(0.5), 1.0);
            system.addObject(box);
        }

        @Override
        public @Nullable Vec3 tick() {
            if (!box.isActive()) return null;
            box.updatePose();
            var center = box.getPose().position();
            return new Vec3(center.x(), center.y(), center.z());
        }

        @Override
        public void remove() {
            system.removeObject(box);
        }
    }

    final class Single implements DropBox {
        private static final double SUBSTEP_SECONDS = 1.0 / 40;
        private final ServerLevel level;
        private AABB bounds;
        private double velocityY;

        Single(ServerLevel level, Vec3 center) {
            this.level = level;
            bounds = AABB.ofSize(center, 1, 1, 1);
        }

        @Override
        public Vec3 tick() {
            // A level, centered drop has no torque. Resolve its one moving axis against block shapes.
            for (int i = 0; i < 2; i++) {
                velocityY -= 11.0 * SUBSTEP_SECONDS;
                double travel = velocityY * SUBSTEP_SECONDS;
                var terrain = level.getBlockCollisions(null, bounds.expandTowards(0, travel, 0));
                double allowed = Shapes.collide(Direction.Axis.Y, bounds, terrain, travel);
                bounds = bounds.move(0, allowed, 0);
                if (allowed > travel) velocityY = 0;
            }
            return bounds.getCenter();
        }
    }
}
