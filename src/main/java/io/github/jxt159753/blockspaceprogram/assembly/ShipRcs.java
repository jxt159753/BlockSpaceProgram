package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Ship-local RCS mounts, shared by Sable flight and the assembly table. */
public final class ShipRcs {
    private ShipRcs() {}

    record Thruster(int part, int group, Vector3dc point, Vector3dc direction, Vector3dc moment,
                    double thrust, double fuelFlow, double electricPower) {
        RcsAllocator.Jet jet(Vector3dc center) {
            return new RcsAllocator.Jet(point.sub(center, new Vector3d()), direction, thrust, fuelFlow);
        }

        boolean supplied(double[] groupFuel, boolean powered) {
            return groupFuel[group] > 0 && (powered || electricPower == 0);
        }
    }

    record Firing(Thruster thruster, double opening) {}

    record Command(List<Firing> firings, Vector3dc netTorque) {}

    static List<Thruster> mounts(ShipDesign design, int[] groups) {
        var mounts = new ArrayList<Thruster>();
        for (int index = 0; index < groups.length; index++) {
            var node = design.nodes().get(index);
            if (node.part() == null) {
                continue;
            }
            for (var thruster : node.part().rcs()) {
                var outlet = thruster.outlet();
                var rating = thruster.performance();
                var point = vector(node.point(outlet.point()));
                var direction = node.pose().rotation().rotate(outlet.exhaust().getOpposite()).step();
                var axis = new Vector3d(direction);
                mounts.add(new Thruster(index, groups[index], point, axis, point.cross(axis, new Vector3d()),
                        rating.thrust(), StagePerformance.fuelFlow(rating.thrust(), rating.isp()), rating.electricPower()));
            }
        }
        return mounts;
    }

    /** Forces indexed by Direction for a fully supplied launch layout. */
    public static double[] pureTranslation(ShipDesign design, double podTorque) {
        int[] groups = StagePerformance.tankGroups(design);
        var mounts = mounts(design, groups);
        if (mounts.isEmpty()) {
            return new double[0];
        }
        var cells = new LinkedHashMap<BlockPos, ShipCollisionLayout.Cell>();
        var firstMoment = new Vector3d();
        var groupFuel = new double[groups.length];
        double electric = 0;
        double wheelTorque = 0;
        for (int i = 0; i < groups.length; i++) {
            var node = design.nodes().get(i);
            var part = node.part();
            if (part == null) {
                node.hull().forEach((pos, material) -> {
                    var world = node.hullOrigin().offset(pos);
                    firstMoment.add(material.mass() * (world.getX() + 0.5),
                            material.mass() * (world.getY() + 0.5), material.mass() * (world.getZ() + 0.5));
                });
                continue;
            }
            double fuel = StagePerformance.fuelCapacity(node);
            groupFuel[groups[i]] += fuel;
            electric += part.properties().electricCapacity();
            wheelTorque += part.properties().wheelTorque() + (node.isCommandPod() ? podTorque : 0);
            var partCells = ShipCollisionLayout.rasterize(part, node.pose().origin(), node.pose().rotation());
            ShipCollisionLayout.addPart(cells, partCells, part.properties().mass() + fuel);
        }
        cells.forEach((pos, cell) -> firstMoment.fma(cell.mass(), vector(cell.center(pos))));
        var center = firstMoment.div(design.fullMass());
        boolean powered = electric > 0;
        var jets = mounts.stream().filter(mount -> mount.supplied(groupFuel, powered)).map(mount -> mount.jet(center)).toList();
        var directions = Direction.values();
        var forces = new double[directions.length];
        for (Direction direction : directions) {
            forces[direction.ordinal()] = RcsAllocator.pureThrust(jets, new Vector3d(direction.step()), wheelTorque);
        }
        return forces;
    }

    private static Vector3d vector(net.minecraft.world.phys.Vec3 point) {
        return new Vector3d(point.x, point.y, point.z);
    }
}
