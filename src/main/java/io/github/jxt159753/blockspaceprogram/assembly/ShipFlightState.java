package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody.SolidBlock;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.Cell;
import io.github.jxt159753.blockspaceprogram.part.PartEngine;
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
    private final ShipParachutes parachutes;
    private final double[] fuel;
    private final double[] electric;
    private final double[] groupFuel;
    private final PropellantFlow supply;
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

    ShipFlightState(ShipDesign design, ShipPropellantData saved, long savedDamage, int stage) {
        int count = design.nodes().size();
        parachutes = new ShipParachutes(design);
        fuel = new double[count];
        electric = new double[count];
        groupFuel = new double[count];
        supply = new PropellantFlow(count);
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
            fuel[index] = ShipPropellantData.initialAmount(saved.fuel(), count, index, capacity, isDamaged(index));
            electric[index] = ShipPropellantData.initialAmount(saved.electric(), count, index,
                    properties.electricCapacity(), isDamaged(index));

            var partCells = ShipCollisionLayout.rasterize(part, node.pose().origin(), node.pose().rotation());
            ShipCollisionLayout.addPart(cells, partCells, properties.mass() + fuel[index] + extraMass);
            extraMass = 0;
            if (capacity > 0) {
                tanks.add(new Tank(index, group, partCells));
            }
            if (hasEngine(node)) {
                for (PartEngine engine : part.engines()) {
                    Vec3 direction = thrustDirection(node, engine);
                    Vec3 point = node.point(engine.outlet().point());
                    engines.add(new Engine(index, group, node.engineStage(), engine,
                            direction.toVector3f(), point.cross(direction).toVector3f()));
                }
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
        int firstParachute = design.nodes().stream().mapToInt(ShipDesign.Node::parachuteStage)
                .filter(stage -> stage >= 0).min().orElse(design.stageCount());
        return Math.min(engineStage, Math.min(firstCut, firstParachute) - 1);
    }

    static double flightDryMass(double dryMass) {
        return dryMass == 0 ? MASSLESS_FRAGMENT_DRY_MASS : dryMass;
    }

    static boolean hasEngine(ShipDesign.Node node) {
        return node.engineStage() >= 0 && node.part() != null && !node.part().engines().isEmpty();
    }

    static boolean hasIgnited(int engineStage, int stage) {
        return engineStage >= 0 && engineStage <= stage;
    }

    static Vec3 thrustDirection(ShipDesign.Node node, PartEngine engine) {
        Direction thrustSide = node.pose().rotation().rotate(engine.outlet().exhaust().getOpposite());
        return Vec3.atLowerCornerOf(thrustSide.getNormal());
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

    ShipParachutes parachutes() {
        return parachutes;
    }

    ShipFlightState withGraph(ShipDesign design, int stage) {
        var updated = new ShipFlightState(design, savePropellant(), damagedParts, stage);
        for (int index = 0; index < fuel.length; index++) {
            updated.parachutes.copyNode(parachutes, index, index);
        }
        return updated;
    }

    ShipFlightState fragment(ShipDesign design, List<Integer> members, int stage) {
        var remainingFuel = new ListTag();
        var remainingElectric = new ListTag();
        long damage = 0;
        for (int part : members) {
            if (isDamaged(part)) {
                damage |= 1L << remainingFuel.size();
            }
            remainingFuel.add(DoubleTag.valueOf(fuel[part]));
            remainingElectric.add(DoubleTag.valueOf(electric[part]));
        }
        var fragment = new ShipFlightState(design, new ShipPropellantData(remainingFuel, remainingElectric), damage, stage);
        for (int index = 0; index < members.size(); index++) {
            fragment.parachutes.copyNode(parachutes, members.get(index), index);
        }
        return fragment;
    }

    ShipFlightState docked(ShipDesign design, ShipFlightState incoming) {
        var combined = savePropellant();
        var added = incoming.savePropellant();
        combined.fuel().addAll(added.fuel());
        combined.electric().addAll(added.electric());
        var docked = new ShipFlightState(design, combined, damagedParts | incoming.damagedParts << fuel.length, stage);
        for (int index = 0; index < fuel.length; index++) {
            docked.parachutes.copyNode(parachutes, index, index);
        }
        for (int index = 0; index < incoming.fuel.length; index++) {
            docked.parachutes.copyNode(incoming.parachutes, index, fuel.length + index);
        }
        return docked;
    }

    double wheelTorque() {
        return POD_WHEEL_TORQUE * Long.bitCount(commandPods & ~damagedParts);
    }

    long damagedParts() {
        return damagedParts;
    }

    boolean isDamaged(int part) {
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
        for (int part = 0; part < electric.length; part++) {
            if ((damage & (1L << part)) != 0) {
                electric[part] = 0;
            }
        }
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

    double remainingElectric() {
        return Arrays.stream(electric).sum();
    }

    ShipPropellantData savePropellant() {
        return new ShipPropellantData(ShipPropellantData.encode(fuel), ShipPropellantData.encode(electric));
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
        boolean powered = remainingElectric() > 0;
        for (Engine engine : engines) {
            if (ignited(engine) && groupFuel[engine.group] > 0 && (powered || engine.spec.electricPower() == 0)) {
                thrust.fma(engine.spec.performance().thrust(), engine.direction);
            }
        }
        return thrust;
    }

    private boolean ignited(Engine engine) {
        return hasIgnited(engine.stage, stage) && !isDamaged(engine.part);
    }

    Depletion burn(double throttle, double pressure, double seconds, ShipCollisionBody body,
            Vector3d impulse, Vector3d momentAboutOrigin) {
        if (throttle == 0 || engines.isEmpty()) {
            return Depletion.NONE;
        }
        collectGroupFuel();
        double charge = remainingElectric();
        supply.reset(charge);
        for (int group = 0; group < groupFuel.length; group++) {
            supply.addFuel(group, groupFuel[group]);
        }
        for (Engine engine : engines) {
            if (!ignited(engine)) {
                continue;
            }
            var rating = engine.spec.performance();
            double vacuum = rating.vacuumSpecificImpulse();
            double seaLevel = rating.seaLevelSpecificImpulse() > 0 ? rating.seaLevelSpecificImpulse() : vacuum;
            double isp = Mth.lerp(pressure, vacuum, seaLevel);
            supply.addEngine(engine.group, StagePerformance.fuelFlow(rating.thrust(), isp) * throttle,
                    engine.spec.electricPower() * throttle);
        }
        boolean fuelDepleted = false;
        while (seconds > 0) {
            double duration = supply.nextSegment(seconds);
            if (!(duration > 0) || !(supply.flowRate() > 0)) {
                break;
            }
            boolean powered = supply.electric() > 0;
            for (Engine engine : engines) {
                if (ignited(engine) && supply.feeding(engine.group) && (powered || engine.spec.electricPower() == 0)) {
                    double magnitude = engine.spec.performance().thrust() * throttle * duration;
                    impulse.fma(magnitude, engine.direction);
                    momentAboutOrigin.fma(magnitude, engine.moment);
                }
            }
            fuelDepleted |= supply.consume(duration);
            seconds -= duration;
        }
        boolean burned = false;
        for (Tank tank : tanks) {
            int group = tank.group;
            if (fuel[tank.part] > 0 && supply.fuel(group) < groupFuel[group]) {
                burned |= setFuel(tank, fuel[tank.part] * (supply.fuel(group) / groupFuel[group]), body);
            }
        }
        if (burned) {
            body.subLevel().updateMergedMassData(0);
        }
        if (supply.electric() < charge) {
            double fraction = supply.electric() / charge;
            for (int part = 0; part < electric.length; part++) {
                electric[part] *= fraction;
            }
        }
        boolean electricDepleted = charge > 0 && supply.electric() == 0;
        return fuelDepleted || electricDepleted ? new Depletion(fuelDepleted, electricDepleted) : Depletion.NONE;
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

    private record Engine(int part, int group, int stage, PartEngine spec, Vector3fc direction, Vector3fc moment) {}

    record Depletion(boolean fuel, boolean electric) {
        private static final Depletion NONE = new Depletion(false, false);
    }
}
