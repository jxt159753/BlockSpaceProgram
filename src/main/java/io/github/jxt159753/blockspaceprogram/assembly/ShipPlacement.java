package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Endpoint;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Link;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Node;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Shared by the viewport preview and the authoritative placement handler. */
public final class ShipPlacement {
    private ShipPlacement() {}

    public record Request(int target, int targetMarker, int partMarker, Direction targetNormal, Direction partNormal,
                          Vec3 hit, int turns, int symmetry) {}

    public record Result(ShipDesign design, @Nullable String error, int skippedMirrors, @Nullable Node candidate) {}

    public record Source(int marker, Direction normal) {}

    public static List<Source> sources(BakedPart part, boolean stack) {
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < part.connectors().size(); i++) {
            var connector = part.connectors().get(i);
            if (connector.kind().isStack() == stack) {
                sources.add(new Source(i, connector.normal()));
            }
        }
        return !stack && sources.isEmpty()
                ? Arrays.stream(Direction.values()).map(normal -> new Source(-1, normal)).toList() : sources;
    }

    public static Result place(ShipDesign design, BakedPart part, Request request) {
        if (part.connectors().stream().anyMatch(connector -> !connector.allowsScale(part.scale()))) {
            return rejected(design, "connector_size");
        }
        if (design.nodes().isEmpty()) {
            if (!part.types().contains(PartType.COMMAND_POD)) {
                return rejected(design, "root");
            }
            Node root = Node.placed(part, PartPose.ROOT);
            return new Result(new ShipDesign(List.of(root), List.of(), design.stageCount()), null, 0, root);
        }
        if (design.nodes().size() == ShipDesign.MAX_PARTS) {
            return rejected(design, "part_limit");
        }
        if (request.target() < 0 || request.target() >= design.nodes().size() || request.turns() < 0 || request.turns() > 3
                || request.symmetry() != 1 && request.symmetry() != 2 && request.symmetry() != 4) {
            return rejected(design, "target");
        }

        Node target = design.nodes().get(request.target());
        boolean surface = request.targetMarker() == -1;
        Face source = face(part, request.partMarker(), request.partNormal(), surface);
        if (source == null) {
            return rejected(design, "source");
        }
        Direction localNormal = target.pose().localDirection(request.targetNormal());
        Vec3 meetingPoint;
        if (surface) {
            AABB targetFace = boxFace(target.bounds(), request.targetNormal());
            Vec3 point = request.hit();
            meetingPoint = switch (request.targetNormal().getAxis()) {
                case X -> new Vec3(targetFace.minX, point.y, point.z);
                case Y -> new Vec3(point.x, targetFace.minY, point.z);
                case Z -> new Vec3(point.x, point.y, targetFace.minZ);
            };
            if (!contains(targetFace, new AABB(meetingPoint, meetingPoint))) {
                return rejected(design, "target");
            }
        } else {
            PartConnector connector = marker(target.part(), request.targetMarker());
            if (connector == null || !connector.kind().isStack() || !connector.allowsScale(target.part().scale())) {
                return rejected(design, "target");
            }
            localNormal = connector.normal();
            meetingPoint = target.point(connector.center());
        }
        Direction outward = target.pose().rotation().rotate(localNormal);
        Direction tangent = target.pose().rotation().rotate(PartConnector.tangent(localNormal));
        OctahedralGroup rotation = PartPose.align(source.normal(), PartConnector.tangent(source.normal()),
                outward.getOpposite(), turn(tangent, outward, request.turns()));
        Vec3 origin = meetingPoint.subtract(PartPose.rotate(rotation, part.modelToLocal(source.center())));
        var pose = new PartPose(PartPose.snap(origin), rotation);
        Node first = Node.placed(part, pose);
        if (!pose.valid() || !surface && pose.position().distanceToSqr(origin) != 0) {
            return new Result(design, "grid", 0, first);
        }
        if (overlaps(design.nodes(), first)) {
            return new Result(design, "overlap", 0, first);
        }
        var nodes = new ArrayList<>(design.nodes());
        var links = new ArrayList<>(design.links());
        Link link = match(nodes, links, request.target(), request.targetMarker(), first, request.partMarker(), source);
        if (link == null) {
            return new Result(design, surface ? "surface_fit" : "stack_fit", 0, first);
        }
        nodes.add(first);
        links.add(link);

        Vec3 axis = design.nodes().getFirst().bounds().getCenter();
        int skipped = 0;
        for (int copy = 1; copy < request.symmetry(); copy++) {
            int turns = copy * 4 / request.symmetry();
            OctahedralGroup spin = PartPose.align(Direction.UP, Direction.EAST, Direction.UP, turn(Direction.EAST, Direction.UP, turns));
            Vec3 rotatedOrigin = axis.add(PartPose.rotate(spin, pose.position().subtract(axis)));
            OctahedralGroup rotated = PartPose.align(Direction.UP, Direction.EAST,
                    spin.rotate(rotation.rotate(Direction.UP)), spin.rotate(rotation.rotate(Direction.EAST)));
            PartPose mirrorPose = new PartPose(PartPose.snap(rotatedOrigin), rotated);
            Node mirror = Node.placed(part, mirrorPose);
            if (nodes.size() == ShipDesign.MAX_PARTS || !mirrorPose.valid()
                    || mirrorPose.position().distanceToSqr(rotatedOrigin) != 0 || overlaps(nodes, mirror)) {
                skipped++;
                continue;
            }
            Link mirroredLink = findMirror(design.nodes().size(), nodes, links, mirror, request.partMarker(), source, surface);
            if (mirroredLink == null) {
                skipped++;
                continue;
            }
            nodes.add(mirror);
            links.add(mirroredLink);
        }
        return new Result(new ShipDesign(nodes, links, design.stageCount()), null, skipped, first);
    }

    private static Result rejected(ShipDesign design, String reason) {
        return new Result(design, reason, 0, null);
    }

    private static boolean overlaps(List<Node> nodes, Node candidate) {
        AABB bounds = candidate.bounds();
        return nodes.stream().anyMatch(node -> node.bounds().intersects(bounds));
    }

    @Nullable
    private static Link findMirror(int targets, List<Node> nodes, List<Link> links, Node mirror, int partMarker,
            Face source, boolean surface) {
        for (int target = 0; target < targets; target++) {
            if (surface) {
                Link link = match(nodes, links, target, -1, mirror, partMarker, source);
                if (link != null) {
                    return link;
                }
            } else {
                for (int index = 0; index < nodes.get(target).part().connectors().size(); index++) {
                    Link link = match(nodes, links, target, index, mirror, partMarker, source);
                    if (link != null) {
                        return link;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static Link match(List<Node> nodes, List<Link> links, int targetIndex, int targetMarker, Node placed,
            int partMarker, Face source) {
        Node target = nodes.get(targetIndex);
        Direction outward = placed.pose().rotation().rotate(source.normal()).getOpposite();
        Direction targetNormal = target.pose().localDirection(outward);
        Vec3 touch = placed.point(source.center());
        Vec3 targetAnchor;
        boolean separable = false;
        if (targetMarker == -1) {
            if (!contains(boxFace(target.bounds(), outward), placed.transform(source.bounds()))) {
                return null;
            }
            targetAnchor = target.pose().localPoint(touch).scale(1.0 / target.part().scale());
        } else {
            PartConnector connector = marker(target.part(), targetMarker);
            PartConnector incoming = marker(placed.part(), partMarker);
            if (connector == null || incoming == null || !connector.matchesStackSize(incoming) || connector.normal() != targetNormal
                    || target.point(connector.center()).distanceToSqr(touch) != 0
                    || !connector.allowsScale(target.part().scale())
                    || links.stream().anyMatch(link -> link.uses(targetIndex, targetMarker))) {
                return null;
            }
            targetAnchor = connector.center();
            separable = connector.kind().separable() || incoming.kind().separable();
        }
        return new Link(new Endpoint(targetIndex, targetMarker, targetNormal, targetAnchor),
                new Endpoint(nodes.size(), partMarker, source.normal(), source.center()), targetMarker == -1,
                separable, separable ? 0 : -1);
    }

    static boolean validLink(List<Node> nodes, Link link) {
        Endpoint first = link.first();
        Endpoint second = link.second();
        Node target = nodes.get(first.node());
        Node placed = nodes.get(second.node());
        Face source = face(placed.part(), second.marker(), second.normal(), link.surface());
        Direction outward = target.pose().rotation().rotate(first.normal());
        if (source == null || source.normal() != second.normal()
                || source.center().distanceToSqr(second.anchor()) != 0
                || placed.pose().rotation().rotate(second.normal()) != outward.getOpposite()
                || target.point(first.anchor()).distanceToSqr(placed.point(second.anchor())) != 0) {
            return false;
        }
        if (link.surface()) {
            return first.marker() == -1 && !link.separable()
                    && contains(boxFace(target.bounds(), outward), placed.transform(source.bounds()));
        }
        PartConnector targetConnector = marker(target.part(), first.marker());
        PartConnector sourceConnector = marker(placed.part(), second.marker());
        return targetConnector != null && sourceConnector != null && targetConnector.matchesStackSize(sourceConnector)
                && targetConnector.normal() == first.normal() && targetConnector.center().distanceToSqr(first.anchor()) == 0
                && (!link.docking() || targetConnector.kind().dockable() && sourceConnector.kind().dockable())
                && link.separable() == (targetConnector.kind().separable() || sourceConnector.kind().separable());
    }

    @Nullable
    private static PartConnector marker(BakedPart part, int index) {
        return index >= 0 && index < part.connectors().size() ? part.connectors().get(index) : null;
    }

    @Nullable
    private static Face face(BakedPart part, int index, Direction normal, boolean surface) {
        if (surface && index == -1 && part.connectors().stream().noneMatch(connector -> !connector.kind().isStack())) {
            AABB bounds = boxFace(new AABB(Vec3.ZERO, part.geometry().size()), normal);
            return new Face(normal, bounds.getCenter(), bounds);
        }
        PartConnector connector = marker(part, index);
        if (connector == null || connector.kind().isStack() == surface) {
            return null;
        }
        return new Face(connector.normal(), connector.center(), connector.modelFace());
    }

    static Direction turn(Direction tangent, Direction axis, int turns) {
        for (int i = 0; i < turns; i++) {
            tangent = axis.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? tangent.getClockWise(axis.getAxis()) : tangent.getCounterClockWise(axis.getAxis());
        }
        return tangent;
    }

    static boolean coversHullFace(AABB part, BlockPos block, Direction outward) {
        return contains(boxFace(part, outward.getOpposite()), boxFace(new AABB(block), outward));
    }

    private static AABB boxFace(AABB box, Direction normal) {
        boolean positive = normal.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        return switch (normal.getAxis()) {
            case X -> new AABB(positive ? box.maxX : box.minX, box.minY, box.minZ,
                    positive ? box.maxX : box.minX, box.maxY, box.maxZ);
            case Y -> new AABB(box.minX, positive ? box.maxY : box.minY, box.minZ,
                    box.maxX, positive ? box.maxY : box.minY, box.maxZ);
            case Z -> new AABB(box.minX, box.minY, positive ? box.maxZ : box.minZ,
                    box.maxX, box.maxY, positive ? box.maxZ : box.minZ);
        };
    }

    private static boolean contains(AABB outer, AABB inner) {
        return outer.minX <= inner.minX && inner.maxX <= outer.maxX && outer.minY <= inner.minY && inner.maxY <= outer.maxY
                && outer.minZ <= inner.minZ && inner.maxZ <= outer.maxZ;
    }

    private record Face(Direction normal, Vec3 center, AABB bounds) {}
}
