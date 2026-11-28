package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody.SolidBlock;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.Cell;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    private static final double MASSLESS_FRAGMENT_DRY_MASS = 0.01;
    private final List<Engine> engines = new ArrayList<>();
    private final List<Tank> tanks = new ArrayList<>();
    private final double[] fuel;
    private final double[] groupFuel;
    private final double[] demand;
    private final double[] burnFraction;
    private final ImpactPart[] impactParts;
    private final Map<BlockPos, Cell> cells;
    private final Map<BlockPos, SolidBlock> hull;
    private final Vec3 seat;
    private final Vector3d projectedDragArea = new Vector3d();
    private final double weakestPart;
    private final long commandPods;
    private int stage;
    private long damagedParts;
    private long pendingDamage;

    ShipFlightState(ShipDesign design, ListTag savedFuel, long savedDamage, int stage) {
        int count = design.nodes().size();
        fuel = new double[count];
        groupFuel = new double[count];
        demand = new double[count];
        burnFraction = new double[count];
        impactParts = new ImpactPart[count];
        this.stage = stage;
        damagedParts = savedDamage & ((1L << count) - 1);
        int[] groups = StagePerformance.tankGroups(design);
        double dryMass = design.nodes().stream().mapToDouble(ShipDesign.Node::dryMass).sum();
        if (!Double.isFinite(dryMass)) {
            throw new IllegalArgumentException("A flying ship needs finite dry mass");
        }
        // A zero-mass fragment still needs a valid rigid body once its fuel is gone.
        double extraMass = flightDryMass(dryMass) - dryMass;
        double weakest = Double.POSITIVE_INFINITY;
        long pods = 0;
        cells = new LinkedHashMap<>();
        var hullBlocks = new LinkedHashMap<BlockPos, SolidBlock>();
        for (ShipDesign.Branch branch : design.tree(Set.of())) {
            int index = branch.node();
            var node = design.nodes().get(index);
            if (node.hull() != null) {
                BlockPos origin = node.hullOrigin();
                node.hull().forEach((pos, material) -> hullBlocks.put(origin.offset(pos),
                        new SolidBlock(material.state(), material.mass())));
                damagedParts &= ~(1L << index);
                continue;
            }
            var part = node.part();
            var properties = part.properties();
            int group = groups[index];
            double capacity = StagePerformance.fuelCapacity(node);
            fuel[index] = initialFuel(savedFuel, count, index, capacity, isDamaged(index));

            var partCells = ShipCollisionLayout.rasterize(part, node.pose().origin(), node.pose().rotation());
            ShipCollisionLayout.addPart(cells, partCells, properties.mass() + fuel[index] + extraMass);
            extraMass = 0;
            if (capacity > 0) {
                tanks.add(new Tank(index, group, partCells));
            }
            if (hasEngine(node)) {
                var geometry = engineGeometry(node);
                engines.add(new Engine(index, group, node.engineStage(), properties, geometry.direction(), geometry.moment()));
            }
            if (node.isCommandPod()) {
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
        if (extraMass > 0) {
            var first = hullBlocks.firstEntry();
            hullBlocks.put(first.getKey(), new SolidBlock(first.getValue().state(), extraMass));
        }
        hull = hullBlocks;
        seat = pods == 0 ? Vec3.ZERO : seat(design, Long.numberOfTrailingZeros(pods));
        weakestPart = weakest;
        commandPods = pods;
    }

    static int initialStage(ShipDesign design) {
        int engineStage = design.nodes().stream().filter(ShipFlightState::hasEngine)
                .mapToInt(ShipDesign.Node::engineStage).min().orElse(0);
        int firstCut = design.links().stream().filter(link -> link.separationStage() >= 0)
                .mapToInt(ShipDesign.Link::separationStage).min().orElse(design.stageCount());
        return Math.min(engineStage, firstCut - 1);
    }

    static double initialFuel(ListTag savedFuel, int partCount, int index, double capacity, boolean damaged) {
        double remaining = savedFuel.size() == partCount ? savedFuel.getDouble(index) : capacity;
        return !damaged && Double.isFinite(remaining) ? Math.clamp(remaining, 0, capacity) : 0;
    }

    static double flightDryMass(double dryMass) {
        return dryMass == 0 ? MASSLESS_FRAGMENT_DRY_MASS : dryMass;
    }

    static boolean hasEngine(ShipDesign.Node node) {
        if (node.engineStage() < 0 || node.part() == null) {
            return false;
        }
        var props = node.part().properties();
        return props.thrust() > 0 && props.vacuumSpecificImpulse() > 0;
    }

    static boolean hasIgnited(int engineStage, int stage) {
        return engineStage >= 0 && engineStage <= stage;
    }

    static Vector3d vacuumThrust(ShipDesign.Node node) {
        return new Vector3d(engineGeometry(node).direction()).mul(node.part().properties().thrust());
    }

    private static EngineGeometry engineGeometry(ShipDesign.Node node) {
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
        return new EngineGeometry(direction.scale(1.0 / nozzleCount).toVector3f(),
                moment.scale(1.0 / nozzleCount).toVector3f());
    }

    static Vec3 seat(ShipDesign design, int pod) {
        var root = design.nodes().get(pod);
        var cabin = root.bounds();
        Vec3 marker = root.part().blocks().stream().filter(block -> block.state().is(PartType.COMMAND_SEATS))
                .map(block -> root.point(Vec3.atCenterOf(block.pos()))).findFirst().orElse(cabin.getCenter());
        var up = root.pose().rotation().rotate(Direction.UP);
        Vec3 seat = exposedSeat(design, marker, up);
        if (cabin.distanceToSqr(seat) > 0.01
                && design.nodes().stream().filter(ShipDesign.Node::isCommandPod).limit(2).count() == 2) {
            // Stacked pods can share a column. Move the covered seat to the closest exposed side.
            for (Direction side : Direction.values()) {
                if (side.getAxis() != up.getAxis()) {
                    Vec3 candidate = exposedSeat(design, marker, side);
                    if (candidate.distanceToSqr(marker) < seat.distanceToSqr(marker)) {
                        seat = candidate;
                    }
                }
            }
        }
        return seat;
    }

    private static Vec3 exposedSeat(ShipDesign design, Vec3 marker, Direction direction) {
        var normal = Vec3.atLowerCornerOf(direction.getNormal());
        double surface = marker.dot(normal);
        for (var node : design.nodes()) {
            var bounds = node.bounds();
            var box = bounds.inflate(0.30);
            Vec3 column = marker.add(normal.scale(box.getCenter().subtract(marker).dot(normal)));
            if (box.contains(column)) {
                var min = new Vec3(bounds.minX, bounds.minY, bounds.minZ);
                var max = new Vec3(bounds.maxX, bounds.maxY, bounds.maxZ);
                surface = Math.max(surface, Math.max(min.dot(normal), max.dot(normal)));
            }
        }
        return marker.add(normal.scale(surface - marker.dot(normal) + 0.05));
    }

    Map<BlockPos, Cell> cells() {
        return cells;
    }

    Map<BlockPos, SolidBlock> hull() {
        return hull;
    }

    Vec3 seat() {
        return seat;
    }

    boolean isDebris() {
        return commandPods == 0;
    }

    int stage() {
        return stage;
    }

    void setStage(int stage) {
        this.stage = stage;
    }

    ShipFlightState fragment(ShipDesign design, List<Integer> members, int stage) {
        var remainingFuel = new ListTag();
        long damage = 0;
        for (int part : members) {
            if (isDamaged(part)) {
                damage |= 1L << remainingFuel.size();
            }
            remainingFuel.add(DoubleTag.valueOf(fuel[part]));
        }
        return new ShipFlightState(design, remainingFuel, damage, stage);
    }

    ShipFlightState docked(ShipDesign design, ShipFlightState incoming) {
        var combinedFuel = saveFuel();
        combinedFuel.addAll(incoming.saveFuel());
        return new ShipFlightState(design, combinedFuel, damagedParts | incoming.damagedParts << fuel.length, stage);
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
            if (part == null || !part.bounds.intersects(local.getX(), local.getY(), local.getZ(),
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

    private void collectGroupFuel() {
        Arrays.fill(groupFuel, 0);
        for (Tank tank : tanks) {
            groupFuel[tank.group] += fuel[tank.part];
        }
    }

    Vector3d availableThrust() {
        var thrust = new Vector3d();
        collectGroupFuel();
        for (Engine engine : engines) {
            if (hasIgnited(engine.stage, stage) && !isDamaged(engine.part) && groupFuel[engine.group] > 0) {
                thrust.fma(engine.properties.thrust(), engine.direction);
            }
        }
        return thrust;
    }

    boolean burn(double throttle, double pressure, double seconds, ShipCollisionBody body,
            Vector3d impulse, Vector3d momentAboutOrigin) {
        if (throttle == 0 || engines.isEmpty()) {
            return false;
        }
        collectGroupFuel();
        Arrays.fill(demand, 0);
        for (Engine engine : engines) {
            if (!hasIgnited(engine.stage, stage) || isDamaged(engine.part)) {
                continue;
            }
            var props = engine.properties;
            double vacuum = props.vacuumSpecificImpulse();
            double seaLevel = props.seaLevelSpecificImpulse() > 0 ? props.seaLevelSpecificImpulse() : vacuum;
            double isp = Mth.lerp(pressure, vacuum, seaLevel);
            demand[engine.group] += StagePerformance.fuelFlow(props.thrust(), isp) * throttle * seconds;
        }
        boolean burned = false;
        boolean stillFed = false;
        for (int group = 0; group < groupFuel.length; group++) {
            burnFraction[group] = demand[group] > 0 ? Math.min(1, groupFuel[group] / demand[group]) : 0;
            stillFed |= demand[group] > 0 && groupFuel[group] > demand[group];
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
            if (hasIgnited(engine.stage, stage) && !isDamaged(engine.part)) {
                double magnitude = engine.properties.thrust() * throttle * seconds * burnFraction[engine.group];
                impulse.fma(magnitude, engine.direction);
                momentAboutOrigin.fma(magnitude, engine.moment);
            }
        }
        return burned && !stillFed;
    }

    private boolean setFuel(Tank tank, double remaining, ShipCollisionBody body) {
        double change = remaining - fuel[tank.part];
        if (change == 0) {
            return false;
        }
        fuel[tank.part] = remaining;
        tank.cells.forEach((pos, cell) -> body.changeCellMass(pos, cells.get(pos), change * cell.mass()));
        return true;
    }

    private record ImpactPart(AABB bounds, double resistance) {}

    private record Tank(int part, int group, Map<BlockPos, Cell> cells) {}

    private record EngineGeometry(Vector3fc direction, Vector3fc moment) {}

    private record Engine(int part, int group, int stage, BlockPartProperties properties, Vector3fc direction, Vector3fc moment) {}
}
