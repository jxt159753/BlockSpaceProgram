package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.Cell;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.PartBox;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3fc;

/** Propellant, part damage, and the ship-local locations at which the flight systems act. */
final class ShipFlightState {
    private static final double POD_WHEEL_TORQUE = 1_500;
    private final List<Engine> engines = new ArrayList<>();
    private final List<Tank> tanks = new ArrayList<>();
    private final double[] fuel;
    private final double[] groupFuel;
    private final double[] demand;
    private final double[] burnFraction;
    private final ImpactPart[] impactParts;
    private final Map<BlockPos, Cell> cells;
    private final Vec3 seat;
    private final Vector3d projectedDragArea = new Vector3d();
    private final double weakestPart;
    private final long commandPods;
    private long damagedParts;
    private long pendingDamage;

    ShipFlightState(ShipDesign design, ListTag savedFuel, long savedDamage) {
        int count = design.nodes().size();
        fuel = new double[count];
        groupFuel = new double[count];
        demand = new double[count];
        burnFraction = new double[count];
        impactParts = new ImpactPart[count];
        damagedParts = savedDamage & ((1L << count) - 1);
        int[] groups = new int[count];
        double dryMass = 0;
        double weakest = Double.POSITIVE_INFINITY;
        long pods = 0;
        int engineStage = design.nodes().stream().filter(ShipFlightState::hasEngine)
                .mapToInt(ShipDesign.Node::engineStage).min().orElse(-1);
        List<PartBox> wetParts = new ArrayList<>(count);
        for (ShipDesign.Branch branch : design.tree(Set.of())) {
            int index = branch.node();
            var node = design.nodes().get(index);
            var part = node.part();
            var properties = part.properties();
            int group = StagePerformance.tankGroup(design, branch, groups);
            groups[index] = group;
            double capacity = properties.fuelCapacity().values().stream().mapToDouble(Double::doubleValue).sum();
            double remaining = savedFuel.size() == count ? savedFuel.getDouble(index) : capacity;
            fuel[index] = !isDamaged(index) && Double.isFinite(remaining) ? Math.clamp(remaining, 0, capacity) : 0;
            dryMass += properties.mass();

            var dry = PartBox.fromPart(part, node.pose().origin(), node.pose().rotation());
            wetParts.add(new PartBox(dry.min(), dry.max(), dry.mass() + fuel[index]));
            if (capacity > 0) {
                var tankCells = new ArrayList<BlockPos>();
                dry.coveredCells().forEach(pos -> tankCells.add(pos.immutable()));
                tanks.add(new Tank(index, group, tankCells));
            }
            if (node.engineStage() == engineStage && hasEngine(node)) {
                engines.add(engine(node, index, group));
            }
            if (part.types().contains(PartType.COMMAND_POD)) {
                pods |= 1L << index;
            }
            var bounds = node.bounds();
            impactParts[index] = new ImpactPart(bounds, properties.impactResistance());
            weakest = Math.min(weakest, properties.impactResistance());
            double x = bounds.getXsize();
            double y = bounds.getYsize();
            double z = bounds.getZsize();
            projectedDragArea.fma(properties.dragCoefficient(), new Vector3d(y * z, x * z, x * y));
        }
        if (!Double.isFinite(dryMass) || dryMass <= 0) {
            throw new IllegalArgumentException("A flying ship needs positive, finite dry mass");
        }
        cells = ShipCollisionLayout.rasterize(wetParts);
        seat = seat(design);
        weakestPart = weakest;
        commandPods = pods;
    }

    private static boolean hasEngine(ShipDesign.Node node) {
        var props = node.part().properties();
        return node.engineStage() >= 0 && props.thrust() > 0 && props.vacuumSpecificImpulse() > 0;
    }

    private static Engine engine(ShipDesign.Node node, int part, int group) {
        Vec3 direction = Vec3.ZERO;
        Vec3 moment = Vec3.ZERO;
        int nozzleCount = 0;
        for (var block : node.part().blocks()) {
            var state = block.state();
            if (state.is(PartType.ENGINE_NOZZLES)) {
                Direction exhaust = state.getOptionalValue(BlockStateProperties.FACING)
                        .or(() -> state.getOptionalValue(BlockStateProperties.HORIZONTAL_FACING)).orElse(Direction.DOWN);
                Direction thrustSide = node.pose().rotation().rotate(exhaust.getOpposite());
                Vec3 thrust = Vec3.atLowerCornerOf(thrustSide.getNormal());
                Vec3 point = node.point(Vec3.atCenterOf(block.pos()).relative(exhaust, 0.5));
                direction = direction.add(thrust);
                moment = moment.add(point.cross(thrust));
                nozzleCount++;
            }
        }
        if (nozzleCount == 0) {
            // Old parts keep their engine role if the original tags are unavailable.
            var bounds = node.part().geometry().size();
            direction = Vec3.atLowerCornerOf(node.pose().rotation().rotate(Direction.UP).getNormal());
            moment = node.point(new Vec3(bounds.x / 2, 0, bounds.z / 2)).cross(direction);
            nozzleCount = 1;
        }
        return new Engine(part, group, node.part().properties(), direction.scale(1.0 / nozzleCount).toVector3f(),
                moment.scale(1.0 / nozzleCount).toVector3f());
    }

    private static Vec3 seat(ShipDesign design) {
        var root = design.nodes().getFirst();
        Vec3 marker = root.part().blocks().stream().filter(block -> block.state().is(PartType.COMMAND_SEATS))
                .map(block -> root.point(Vec3.atCenterOf(block.pos()))).findFirst().orElseGet(() -> root.bounds().getCenter());
        double height = root.bounds().maxY;
        for (var node : design.nodes()) {
            var box = node.bounds().inflate(0.30, 0, 0.30);
            if (marker.x >= box.minX && marker.x <= box.maxX && marker.z >= box.minZ && marker.z <= box.maxZ) {
                height = Math.max(height, box.maxY);
            }
        }
        return new Vec3(marker.x, height + 0.05, marker.z);
    }

    Map<BlockPos, Cell> cells() {
        return cells;
    }

    Vec3 seat() {
        return seat;
    }

    double wheelTorque() {
        return POD_WHEEL_TORQUE * Long.bitCount(commandPods & ~damagedParts);
    }

    long damagedParts() {
        return damagedParts;
    }

    private boolean isDamaged(int part) {
        return (damagedParts & (1L << part)) != 0;
    }

    void recordImpact(BlockPos origin, BlockPos cell, Vector3dc point, double speed) {
        if (speed <= weakestPart) {
            return;
        }
        var contact = new Vec3(point.x() - origin.getX(), point.y() - origin.getY(), point.z() - origin.getZ());
        BlockPos local = cell.subtract(origin);
        double nearestDistance = Double.POSITIVE_INFINITY;
        long hits = 0;
        for (int i = 0; i < impactParts.length; i++) {
            ImpactPart part = impactParts[i];
            if (!part.bounds.intersects(local.getX(), local.getY(), local.getZ(),
                    local.getX() + 1, local.getY() + 1, local.getZ() + 1)) {
                continue;
            }
            // Several parts can share a collision cell. Use the closest part bounds to the contact point.
            double distance = part.bounds.distanceToSqr(contact);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                hits = 0;
            }
            if (distance == nearestDistance && speed > part.resistance) {
                hits |= 1L << i;
            }
        }
        pendingDamage |= hits;
    }

    boolean applyImpactDamage(ShipCollisionBody body) {
        long damage = pendingDamage & ~damagedParts;
        pendingDamage = 0;
        if (damage == 0) {
            return false;
        }
        damagedParts |= damage;
        boolean drained = false;
        for (Tank tank : tanks) {
            if ((damage & (1L << tank.part)) != 0) {
                drained |= setFuel(tank, 0, body);
            }
        }
        if (drained) {
            body.subLevel().updateMergedMassData(0);
        }
        return true;
    }

    double dragArea(Vector3dc unitDirection) {
        return Math.abs(unitDirection.x()) * projectedDragArea.x + Math.abs(unitDirection.y()) * projectedDragArea.y
                + Math.abs(unitDirection.z()) * projectedDragArea.z;
    }

    double remainingFuel() {
        return Arrays.stream(fuel).sum();
    }

    ListTag saveFuel() {
        var tag = new ListTag();
        for (double remaining : fuel) {
            tag.add(DoubleTag.valueOf(remaining));
        }
        return tag;
    }

    void burn(double throttle, double pressure, double seconds, ShipCollisionBody body,
            Vector3d impulse, Vector3d momentAboutOrigin) {
        if (throttle == 0 || engines.isEmpty()) {
            return;
        }
        Arrays.fill(groupFuel, 0);
        Arrays.fill(demand, 0);
        for (Tank tank : tanks) {
            groupFuel[tank.group] += fuel[tank.part];
        }
        for (Engine engine : engines) {
            if (isDamaged(engine.part)) {
                continue;
            }
            var props = engine.properties;
            double vacuum = props.vacuumSpecificImpulse();
            double seaLevel = props.seaLevelSpecificImpulse() > 0 ? props.seaLevelSpecificImpulse() : vacuum;
            double isp = Mth.lerp(pressure, vacuum, seaLevel);
            demand[engine.group] += StagePerformance.fuelFlow(props.thrust(), isp) * throttle * seconds;
        }
        boolean burned = false;
        for (int group = 0; group < groupFuel.length; group++) {
            burnFraction[group] = demand[group] > 0 ? Math.min(1, groupFuel[group] / demand[group]) : 0;
        }
        for (Tank tank : tanks) {
            int group = tank.group;
            if (fuel[tank.part] > 0 && demand[group] > 0) {
                double keep = Math.max(0, 1 - demand[group] / groupFuel[group]);
                burned |= setFuel(tank, fuel[tank.part] * keep, body);
            }
        }
        if (burned) {
            body.subLevel().updateMergedMassData(0);
        }
        for (Engine engine : engines) {
            if (!isDamaged(engine.part)) {
                double magnitude = engine.properties.thrust() * throttle * seconds * burnFraction[engine.group];
                impulse.fma(magnitude, engine.direction);
                momentAboutOrigin.fma(magnitude, engine.moment);
            }
        }
    }

    private boolean setFuel(Tank tank, double remaining, ShipCollisionBody body) {
        double change = remaining - fuel[tank.part];
        if (change == 0) {
            return false;
        }
        fuel[tank.part] = remaining;
        double deltaPerCell = change / tank.cells.size();
        for (BlockPos pos : tank.cells) {
            body.changeCellMass(pos, cells.get(pos), deltaPerCell);
        }
        return true;
    }

    private record ImpactPart(AABB bounds, double resistance) {}

    private record Tank(int part, int group, List<BlockPos> cells) {}

    private record Engine(int part, int group, BlockPartProperties properties, Vector3fc direction, Vector3fc moment) {}
}
