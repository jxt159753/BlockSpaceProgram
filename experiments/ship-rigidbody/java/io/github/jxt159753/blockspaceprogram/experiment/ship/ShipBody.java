package io.github.jxt159753.blockspaceprogram.experiment.ship;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.PartBox;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

record ShipBody(ShipCollisionBody body) {
    static ShipBody spawn(ServerLevel level, String name, List<PartBox> parts,
            Vector3dc worldOrigin, Quaterniondc orientation) {
        var body = ShipCollisionBody.spawn(level, name, parts, worldOrigin, orientation);
        body.container().addForceLoadTicket(body.subLevel(), SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        return new ShipBody(body);
    }

    ShipBody rebuild(List<PartBox> parts, Motion previous) {
        ShipBody replacement = spawn(subLevel().getLevel(), subLevel().getName(), parts,
                pointInWorld(previous.pose, new Vector3d()), previous.pose.orientation());
        try {
            replacement.pipeline().addLinearAndAngularVelocity(replacement.subLevel(),
                    previous.velocityAt(replacement.subLevel().logicalPose().position()), previous.angular);
        } catch (RuntimeException error) {
            replacement.remove();
            throw error;
        }
        remove();
        return replacement;
    }

    ServerSubLevel subLevel() {
        return body.subLevel();
    }

    BlockPos plotOrigin() {
        return body.plotOrigin();
    }

    PhysicsPipeline pipeline() {
        return body.pipeline();
    }

    Motion motion() {
        if (subLevel().isRemoved()) {
            throw new IllegalStateException("Sable removed " + subLevel().getName());
        }
        Pose3d pose = pipeline().readPose(subLevel(), new Pose3d(subLevel().logicalPose()));
        return new Motion(pose, pipeline().getLinearVelocity(subLevel(), new Vector3d()),
                pipeline().getAngularVelocity(subLevel(), new Vector3d()));
    }

    Vector3d pointInWorld(Pose3d pose, Vector3d point) {
        BlockPos origin = plotOrigin();
        return pose.transformPosition(point.add(origin.getX(), origin.getY(), origin.getZ()));
    }

    void remove() {
        body.remove();
    }

    record Motion(Pose3d pose, Vector3d linear, Vector3d angular) {
        Vector3d velocityAt(Vector3dc worldPoint) {
            Vector3d offset = worldPoint.sub(pose.position(), new Vector3d());
            return angular.cross(offset, offset).add(linear);
        }
    }
}
