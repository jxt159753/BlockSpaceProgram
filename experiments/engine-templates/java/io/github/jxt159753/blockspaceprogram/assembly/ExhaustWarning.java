package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartEngine;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Detects a part in the first unscaled block along an engine's exhaust axis. */
public final class ExhaustWarning {
    public static final String MESSAGE = "exhaust_blocked";

    private ExhaustWarning() {}

    public static boolean blocked(ShipDesign design) {
        for (var node : design.nodes()) {
            if (node.part() == null) {
                continue;
            }
            for (var ray : rays(node.part(), node.pose().position(), node.pose().rotation())) {
                for (var other : design.nodes()) {
                    if (other != node && other.part() != null && ray.intersects(other.bounds())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean blocked(Level level, BakedPart candidate, WorldPartPlacement.Placement placement) {
        AABB bounds = placement.bounds();
        var candidateRays = rays(candidate, placement.origin(), placement.rotation());
        for (var other : level.getEntitiesOfClass(PlacedPartEntity.class, bounds.inflate(BakedPart.MAX_SIDE + 1),
                PlacedPartEntity::isPickable)) {
            if (candidateRays.stream().anyMatch(ray -> ray.intersects(other.getBoundingBox()))
                    || rays(other.part(), other.position(), other.rotation()).stream().anyMatch(bounds::intersects)) {
                return true;
            }
        }
        return false;
    }

    private static List<AABB> rays(BakedPart part, Vec3 origin, OctahedralGroup rotation) {
        return PartEngine.forPart(part).stream().map(engine -> {
            var outlet = engine.outlet();
            Vec3 start = origin.add(PartPose.rotate(rotation, part.modelToLocal(outlet.point())));
            Vec3 end = start.relative(rotation.rotate(outlet.exhaust()), part.scale());
            return new AABB(start, end);
        }).toList();
    }
}
