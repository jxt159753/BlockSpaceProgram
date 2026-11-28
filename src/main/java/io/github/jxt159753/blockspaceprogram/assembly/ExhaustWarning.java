package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Checks one scaled block along the axis from each baked outlet. */
final class ExhaustWarning {
    static final String MESSAGE = "exhaust_blocked";
    private static final double EDGE_TOLERANCE = 1e-7;

    private ExhaustWarning() {}

    static boolean blocked(ShipDesign design, int firstPlaced) {
        var nodes = design.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            if (node.part() == null) {
                continue;
            }
            for (AABB ray : rays(node.part(), node.pose().position(), node.pose().rotation())) {
                for (int j = i < firstPlaced ? firstPlaced : 0; j < nodes.size(); j++) {
                    var other = nodes.get(j);
                    if (i != j && other.part() != null && ray.intersects(other.bounds())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean blocked(PlacedPartEntity placed) {
        AABB bounds = placed.getBoundingBox();
        var outgoing = rays(placed.part(), placed.position(), placed.rotation());
        // An outlet is inside its part's bounds and extends at most a single full-scale block.
        var neighbors = placed.level().getEntitiesOfClass(PlacedPartEntity.class, bounds.inflate(1 + EDGE_TOLERANCE),
                other -> other != placed && other.isPickable());
        for (var other : neighbors) {
            if (outgoing.stream().anyMatch(ray -> ray.intersects(other.getBoundingBox()))
                    || rays(other.part(), other.position(), other.rotation()).stream().anyMatch(bounds::intersects)) {
                return true;
            }
        }
        return false;
    }

    private static List<AABB> rays(BakedPart part, Vec3 origin, OctahedralGroup rotation) {
        return part.engines().stream().map(engine -> {
            var outlet = engine.outlet();
            var exhaust = rotation.rotate(outlet.exhaust());
            Vec3 start = origin.add(PartPose.rotate(rotation, part.modelToLocal(outlet.point())));
            Vec3 end = start.relative(exhaust, part.scale());
            // Widen across the axis so a shared edge can still obstruct the outlet.
            return new AABB(start, end).inflate(exhaust.getStepX() == 0 ? EDGE_TOLERANCE : 0,
                    exhaust.getStepY() == 0 ? EDGE_TOLERANCE : 0, exhaust.getStepZ() == 0 ? EDGE_TOLERANCE : 0);
        }).toList();
    }
}
