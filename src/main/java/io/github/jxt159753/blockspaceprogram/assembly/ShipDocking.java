package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Endpoint;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Link;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Node;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;

/** Docking ports and exact, quarter-grid transforms between ship layouts. */
final class ShipDocking {
    private ShipDocking() {}

    record PortKey(int node, int marker) {
        PortKey at(int index) {
            return new PortKey(index, marker);
        }
    }

    record Port(Endpoint endpoint, Vec3 center, Direction normal, Direction tangent, int size) {
        PortKey key() {
            return new PortKey(endpoint.node(), endpoint.marker());
        }
    }

    record CaptureLimits(double distanceSquared, double speedSquared, double alignmentDot) {
        static CaptureLimits of(double distance, double speed, double angle) {
            return new CaptureLimits(distance * distance, speed * speed, Math.cos(Math.toRadians(angle) / 2));
        }
    }

    static List<Port> openPorts(ShipDesign design, long damage, Set<PortKey> released) {
        var ports = new ArrayList<Port>();
        for (int index = 0; index < design.nodes().size(); index++) {
            var node = design.nodes().get(index);
            if (node.part() == null || (damage & 1L << index) != 0) {
                continue;
            }
            var connectors = node.part().connectors();
            for (int marker = 0; marker < connectors.size(); marker++) {
                var connector = connectors.get(marker);
                if (connector.kind().dockable() && connector.allowsScale(node.part().scale())
                        && !released.contains(new PortKey(index, marker)) && !design.isMarkerUsed(index, marker)) {
                    var rotation = node.pose().rotation();
                    ports.add(new Port(new Endpoint(index, marker, connector.normal(), connector.center()),
                            node.point(connector.center()), rotation.rotate(connector.normal()),
                            rotation.rotate(PartConnector.tangent(connector.normal())), connector.sizeQuarters()));
                }
            }
        }
        return ports;
    }

    @Nullable
    static PartPose capture(Port first, Port second, Quaterniondc relative, Vec3 separation, Vec3 relativeSpeed,
            CaptureLimits limits) {
        if (first.size() != second.size() || separation.lengthSqr() > limits.distanceSquared()
                || relativeSpeed.lengthSqr() > limits.speedSquared()) {
            return null;
        }
        double bestDot = limits.alignmentDot();
        PartPose best = null;
        for (int turns = 0; turns < 4; turns++) {
            var tangent = ShipPlacement.turn(first.tangent(), first.normal(), turns);
            var rotation = PartPose.align(second.normal(), second.tangent(), first.normal().getOpposite(), tangent);
            var candidate = new Quaterniond(PartPose.orientation(rotation)).normalize();
            double dot = Math.abs(candidate.dot(relative));
            if (dot >= bestDot) {
                Vec3 origin = first.center().subtract(PartPose.rotate(rotation, second.center()));
                var frame = new PartPose(PartPose.snap(origin), rotation);
                if (frame.position().distanceToSqr(origin) == 0) {
                    best = frame;
                    bestDot = dot;
                }
            }
        }
        return best;
    }

    @Nullable
    static ShipDesign merge(ShipDesign primary, ShipDesign incoming, PartPose frame, Port first, Port second) {
        var rotation = frame.rotation();
        var translation = frame.origin();
        if (incoming.hasHull() && (rotation.rotate(Direction.UP) != Direction.UP
                || translation.getX() % 4 != 0 || translation.getY() % 4 != 0 || translation.getZ() % 4 != 0)) {
            return null;
        }
        int offset = primary.nodes().size();
        int stageOffset = primary.stageCount();
        var nodes = new ArrayList<>(primary.nodes());
        for (var node : incoming.nodes()) {
            var pose = node.pose().in(frame);
            if (node.hull() == null) {
                nodes.add(node.reframe(pose, stage -> stage + stageOffset));
            } else {
                nodes.add(Node.hull(node.hull().rotate(rotation), BlockPos.containing(pose.position())));
            }
        }
        var links = new ArrayList<>(primary.links());
        for (var link : incoming.links()) {
            links.add(new Link(mergeEndpoint(incoming, frame, offset, link.first()),
                    mergeEndpoint(incoming, frame, offset, link.second()), link.surface(), link.separable(),
                    link.separationStage() < 0 ? -1 : link.separationStage() + stageOffset));
        }
        links.add(new Link(first.endpoint(), second.endpoint().withNode(second.endpoint().node() + offset), false, true, -1));
        return new ShipDesign(nodes, links, stageOffset + incoming.stageCount());
    }

    private static Endpoint mergeEndpoint(ShipDesign incoming, PartPose frame, int offset, Endpoint endpoint) {
        int index = endpoint.node() + offset;
        if (incoming.nodes().get(endpoint.node()).hull() == null) {
            return endpoint.withNode(index);
        }
        // Hull rotation is baked into its blocks, so its attachment faces rotate in node coordinates.
        var rotation = frame.rotation();
        return new Endpoint(index, endpoint.marker(), rotation.rotate(endpoint.normal()), PartPose.rotate(rotation, endpoint.anchor()));
    }

    static ShipDesign closeAlignedPorts(ShipDesign design, long damage, Set<PortKey> released) {
        var ports = openPorts(design, damage, released);
        var links = new ArrayList<>(design.links());
        var used = new HashSet<PortKey>();
        for (int i = 0; i < ports.size(); i++) {
            var first = ports.get(i);
            if (used.contains(first.key())) {
                continue;
            }
            for (int j = i + 1; j < ports.size() && links.size() < ShipDesign.MAX_LINKS; j++) {
                var second = ports.get(j);
                if (first.endpoint().node() != second.endpoint().node() && first.size() == second.size()
                        && first.center().distanceToSqr(second.center()) == 0 && first.normal() == second.normal().getOpposite()
                        && !used.contains(second.key())) {
                    links.add(new Link(first.endpoint(), second.endpoint(), false, true, -1));
                    used.add(second.key());
                    break;
                }
            }
        }
        return links.size() == design.links().size() ? design : new ShipDesign(design.nodes(), links, design.stageCount());
    }
}
