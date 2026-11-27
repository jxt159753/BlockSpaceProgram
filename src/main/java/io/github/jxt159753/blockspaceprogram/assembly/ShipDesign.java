package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.PartBox;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Blueprints start at their root command pod. Flight layouts may also describe detached parts. */
public record ShipDesign(List<Node> nodes, List<Link> links, int stageCount) {
    // With the blueprint in hand, the main inventory and offhand can supply 36 unstackable parts.
    public static final int MAX_PARTS = 36;
    public static final int MAX_HULL_NODES = 27;
    static final int MAX_NODES = MAX_PARTS + MAX_HULL_NODES;
    public static final int BENCHMARK_MAX_PARTS = 50;
    public static final int MAX_STAGES = MAX_PARTS * 2;
    static final int MAX_LINKS = MAX_PARTS * (MAX_PARTS - 1) / 2;
    private static final int MAX_NODE_LINKS = MAX_NODES * (MAX_NODES - 1) / 2;
    static final Codec<Integer> STAGE = Codec.intRange(-1, MAX_STAGES - 1);
    public static final ShipDesign EMPTY = new ShipDesign(List.of(), List.of(), 1);

    public static final Codec<ShipDesign> FLIGHT_CODEC = RecordCodecBuilder.<ShipDesign>create(instance -> instance.group(
            Node.CODEC.listOf(1, MAX_NODES).fieldOf("nodes").forGetter(ShipDesign::nodes),
            Link.CODEC.listOf(0, MAX_NODE_LINKS).fieldOf("links").forGetter(ShipDesign::links),
            Codec.intRange(1, MAX_STAGES).fieldOf("stages").forGetter(ShipDesign::stageCount)
    ).apply(instance, ShipDesign::new)).validate(design -> design.isValidLayout() ? DataResult.success(design)
            : DataResult.error(() -> "Invalid ship layout"));

    public static final Codec<ShipDesign> CODEC = FLIGHT_CODEC.validate(design -> design.hasBlueprintRoot()
            ? DataResult.success(design) : DataResult.error(() -> "Invalid ship blueprint root"));

    public static final StreamCodec<FriendlyByteBuf, ShipDesign> STREAM_CODEC = streamCodec(MAX_PARTS, MAX_LINKS);
    public static final StreamCodec<FriendlyByteBuf, ShipDesign> BENCHMARK_STREAM_CODEC =
            streamCodec(BENCHMARK_MAX_PARTS, BENCHMARK_MAX_PARTS - 1);
    private static final int HULL_STREAM_MARKER = -1;
    private static final StreamCodec<? super FriendlyByteBuf, ShipDesign> HULL_STREAM_CODEC =
            ByteBufCodecs.fromCodec(FLIGHT_CODEC);
    public static final StreamCodec<FriendlyByteBuf, ShipDesign> FLIGHT_STREAM_CODEC = StreamCodec.of((buffer, design) -> {
        if (design.hasHull()) {
            buffer.writeVarInt(HULL_STREAM_MARKER);
            HULL_STREAM_CODEC.encode(buffer, design);
        } else {
            STREAM_CODEC.encode(buffer, design);
        }
    }, buffer -> {
        int start = buffer.readerIndex();
        if (buffer.readVarInt() == HULL_STREAM_MARKER) {
            return HULL_STREAM_CODEC.decode(buffer);
        }
        buffer.readerIndex(start);
        return STREAM_CODEC.decode(buffer);
    });

    private static StreamCodec<FriendlyByteBuf, ShipDesign> streamCodec(int parts, int links) {
        return StreamCodec.composite(
                Node.STREAM_CODEC.apply(ByteBufCodecs.list(parts)), ShipDesign::nodes,
                Link.STREAM_CODEC.apply(ByteBufCodecs.list(links)), ShipDesign::links,
                ByteBufCodecs.VAR_INT, ShipDesign::stageCount, ShipDesign::new);
    }

    public ShipDesign {
        nodes = List.copyOf(nodes);
        links = List.copyOf(links);
    }

    public double fullMass() {
        return nodes.stream()
                .mapToDouble(node -> node.dryMass() + StagePerformance.fuelCapacity(node))
                .sum();
    }

    public boolean hasHull() {
        return nodes.stream().anyMatch(node -> node.hull() != null);
    }

    public int partCount() {
        return (int) nodes.stream().filter(node -> node.part() != null).count();
    }

    public record Node(@Nullable BakedPart part, PartPose pose, int engineStage, @Nullable HullRegion hull) {
        private static final Codec<Node> CODEC = RecordCodecBuilder.<Node>create(instance -> instance.group(
                BakedPart.CODEC.optionalFieldOf("part").forGetter(node -> Optional.ofNullable(node.part())),
                PartPose.CODEC.fieldOf("pose").forGetter(Node::pose),
                STAGE.optionalFieldOf("engine_stage", -1).forGetter(Node::engineStage),
                HullRegion.CODEC.optionalFieldOf("hull").forGetter(node -> Optional.ofNullable(node.hull()))
        ).apply(instance, (part, pose, stage, hull) -> new Node(part.orElse(null), pose, stage, hull.orElse(null))))
                .validate(node -> node.hasGeometry() ? DataResult.success(node)
                        : DataResult.error(() -> "A ship node needs either a part or a hull region"));
        private static final StreamCodec<FriendlyByteBuf, Node> STREAM_CODEC = StreamCodec.composite(
                BakedPart.STREAM_CODEC, Node::part, PartPose.STREAM_CODEC, Node::pose,
                ByteBufCodecs.VAR_INT, Node::engineStage, Node::new);

        public Node(BakedPart part, PartPose pose, int engineStage) {
            this(part, pose, engineStage, null);
        }

        static Node hull(HullRegion hull, BlockPos origin) {
            return new Node(null, new PartPose(origin.multiply(4), PartPose.ROOT.rotation()), -1, hull);
        }

        static Node placed(BakedPart part, PartPose pose) {
            return new Node(part, pose, part.types().contains(PartType.ENGINE) ? 0 : -1);
        }

        private boolean hasGeometry() {
            return (part == null) != (hull == null);
        }

        boolean isCommandPod() {
            return part != null && part.types().contains(PartType.COMMAND_POD);
        }

        double dryMass() {
            return hull == null ? part.properties().mass() : hull.mass();
        }

        public AABB bounds() {
            return hull == null ? pose.bounds(part) : hull.bounds().move(pose.position());
        }

        public Vec3 point(Vec3 modelPoint) {
            return pose.point(hull == null ? part.modelToLocal(modelPoint) : modelPoint);
        }

        BlockPos hullOrigin() {
            return BlockPos.containing(pose.position());
        }

        public AABB transform(AABB modelBounds) {
            return new AABB(point(new Vec3(modelBounds.minX, modelBounds.minY, modelBounds.minZ)),
                    point(new Vec3(modelBounds.maxX, modelBounds.maxY, modelBounds.maxZ)));
        }
    }

    /** Marker -1 denotes a face of the simplified collider. Anchor is in the unscaled baked model. */
    public record Endpoint(int node, int marker, Direction normal, Vec3 anchor) {
        private static final Codec<Endpoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, MAX_NODES - 1).fieldOf("node").forGetter(Endpoint::node),
                Codec.INT.fieldOf("marker").forGetter(Endpoint::marker),
                Direction.CODEC.fieldOf("normal").forGetter(Endpoint::normal),
                Vec3.CODEC.fieldOf("anchor").forGetter(Endpoint::anchor)
        ).apply(instance, Endpoint::new));
        private static final StreamCodec<FriendlyByteBuf, Endpoint> STREAM_CODEC = StreamCodec.of((buffer, endpoint) -> {
            buffer.writeVarInt(endpoint.node());
            buffer.writeVarInt(endpoint.marker());
            buffer.writeEnum(endpoint.normal());
            buffer.writeVec3(endpoint.anchor());
        }, buffer -> new Endpoint(buffer.readVarInt(), buffer.readVarInt(), buffer.readEnum(Direction.class), buffer.readVec3()));

        Endpoint withNode(int index) {
            return new Endpoint(index, marker, normal, anchor);
        }
    }

    public record Link(Endpoint first, Endpoint second, boolean surface, boolean separable, int separationStage) {
        static final Codec<Link> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Endpoint.CODEC.fieldOf("first").forGetter(Link::first),
                Endpoint.CODEC.fieldOf("second").forGetter(Link::second),
                Codec.BOOL.optionalFieldOf("surface", false).forGetter(Link::surface),
                Codec.BOOL.optionalFieldOf("separable", false).forGetter(Link::separable),
                STAGE.optionalFieldOf("separation_stage", -1).forGetter(Link::separationStage)
        ).apply(instance, Link::new));
        private static final StreamCodec<FriendlyByteBuf, Link> STREAM_CODEC = StreamCodec.composite(
                Endpoint.STREAM_CODEC, Link::first, Endpoint.STREAM_CODEC, Link::second,
                ByteBufCodecs.BOOL, Link::surface, ByteBufCodecs.BOOL, Link::separable,
                ByteBufCodecs.VAR_INT, Link::separationStage, Link::new);

        int other(int node) {
            if (first.node() == node) {
                return second.node();
            }
            return second.node() == node ? first.node() : -1;
        }

        boolean uses(int node, int marker) {
            return first.node() == node && first.marker() == marker || second.node() == node && second.marker() == marker;
        }

        boolean docking() {
            return separable && separationStage == -1;
        }

        Link withNodes(int firstIndex, int secondIndex) {
            return new Link(first.withNode(firstIndex), second.withNode(secondIndex), surface, separable, separationStage);
        }

        Link withStage(int stage) {
            return new Link(first, second, surface, separable, stage);
        }
    }

    public record Branch(int node, int parent, int link) {}

    public List<Branch> tree(Set<Integer> detachedLinks) {
        return nodes.isEmpty() ? List.of() : tree(links, 0, detachedLinks, new boolean[nodes.size()]);
    }

    private static List<Branch> tree(List<Link> links, int root, Set<Integer> detachedLinks, boolean[] visited) {
        List<Branch> tree = new ArrayList<>();
        visited[root] = true;
        tree.add(new Branch(root, -1, -1));
        for (int i = 0; i < tree.size(); i++) {
            int parent = tree.get(i).node();
            for (int j = 0; j < links.size(); j++) {
                int next = links.get(j).other(parent);
                if (next >= 0 && !visited[next] && !detachedLinks.contains(j)) {
                    visited[next] = true;
                    tree.add(new Branch(next, parent, j));
                }
            }
        }
        return tree;
    }

    List<List<Integer>> components(Set<Integer> detachedLinks) {
        return components(nodes.size(), links, detachedLinks, -1);
    }

    static List<List<Integer>> components(int nodeCount, List<Link> links, Set<Integer> detachedLinks, int removedNode) {
        boolean[] visited = new boolean[nodeCount];
        if (removedNode >= 0) {
            visited[removedNode] = true;
        }
        List<List<Integer>> components = new ArrayList<>();
        for (int node = 0; node < nodeCount; node++) {
            if (!visited[node]) {
                components.add(tree(links, node, detachedLinks, visited).stream().map(Branch::node).toList());
            }
        }
        return components;
    }

    public AABB bounds() {
        return nodes.stream().map(Node::bounds).reduce(AABB::minmax).orElse(new AABB(0, 0, 0, 1, 1, 1));
    }

    public boolean isMarkerUsed(int node, int marker) {
        return links.stream().anyMatch(link -> link.uses(node, marker));
    }

    public boolean isValid() {
        return hasBlueprintRoot() && isValidLayout();
    }

    private boolean hasBlueprintRoot() {
        return !hasHull() && !nodes.isEmpty() && nodes.getFirst().pose().equals(PartPose.ROOT) && nodes.getFirst().isCommandPod()
                && links.stream().noneMatch(Link::docking);
    }

    boolean isValidLayout() {
        int parts = partCount();
        int hulls = nodes.size() - parts;
        if (nodes.isEmpty() || parts > MAX_PARTS || hulls > MAX_HULL_NODES
                || links.size() > (hulls == 0 ? MAX_LINKS : MAX_NODE_LINKS) || stageCount < 1
                || stageCount > MAX_STAGES) {
            return false;
        }
        Map<BlockPos, Node> hullCells = new HashMap<>();
        for (Node node : nodes) {
            if (!node.hasGeometry()) {
                return false;
            }
            if (node.hull() != null) {
                if (node.engineStage() != -1 || node.pose().rotation() != PartPose.ROOT.rotation()
                        || !node.pose().position().equals(Vec3.atLowerCornerOf(node.hullOrigin()))) {
                    return false;
                }
                int before = hullCells.size();
                BlockPos origin = node.hullOrigin();
                node.hull().forEach((pos, material) -> hullCells.put(origin.offset(pos), node));
                if (hullCells.size() - before != node.hull().size()) {
                    return false;
                }
            }
        }
        List<AABB> bounds = new ArrayList<>(parts);
        for (Node node : nodes) {
            if (node.hull() != null) {
                continue;
            }
            if (!(hulls == 0 ? node.pose().valid() : node.pose().validRotation())
                    || !validStage(node.engineStage(), node.part().types().contains(PartType.ENGINE))) {
                return false;
            }
            if (hulls != 0) {
                for (BlockPos pos : PartBox.fromPart(node.part(), node.pose().origin(), node.pose().rotation()).coveredCells()) {
                    if (hullCells.containsKey(pos)) {
                        return false;
                    }
                }
            }
            AABB box = node.bounds();
            if (bounds.stream().anyMatch(box::intersects)) {
                return false;
            }
            bounds.add(box);
        }
        Set<Endpoint> usedMarkers = new HashSet<>();
        for (Link link : links) {
            if (link.first().node() < 0 || link.first().node() >= nodes.size() || link.second().node() < 0
                    || link.second().node() >= nodes.size() || link.first().node() == link.second().node()
                    || (!link.docking() && !validStage(link.separationStage(), link.separable()))
                    || !validLink(link, hullCells)
                    || (link.first().marker() >= 0 && !usedMarkers.add(link.first()))
                    || (link.second().marker() >= 0 && !usedMarkers.add(link.second()))) {
                return false;
            }
        }
        return tree(Set.of()).size() == nodes.size();
    }

    private boolean validLink(Link link, Map<BlockPos, Node> hullCells) {
        Node first = nodes.get(link.first().node());
        Node second = nodes.get(link.second().node());
        if (first.hull() == null && second.hull() == null) {
            return ShipPlacement.validLink(nodes, link);
        }
        if (!link.surface() || link.separable() || link.first().marker() != -1 || link.second().marker() != -1) {
            return false;
        }
        if (first.hull() == null) {
            return validHullLink(second, link.second(), first, link.first(), hullCells);
        }
        return second.hull() == null && validHullLink(first, link.first(), second, link.second(), hullCells);
    }

    private static boolean validHullLink(Node hull, Endpoint side, Node part, Endpoint attached, Map<BlockPos, Node> hullCells) {
        Direction normal = side.normal();
        Vec3 point = hull.point(side.anchor());
        BlockPos cell = BlockPos.containing(point.relative(normal, -0.5));
        return hullCells.get(cell) == hull && point.distanceToSqr(Vec3.atCenterOf(cell).relative(normal, 0.5)) == 0
                && part.point(attached.anchor()).distanceToSqr(point) == 0
                && part.pose().rotation().rotate(attached.normal()) == normal.getOpposite()
                && ShipPlacement.coversHullFace(part.bounds(), cell, normal);
    }

    private boolean validStage(int stage, boolean hasAction) {
        return hasAction ? stage >= 0 && stage < stageCount : stage == -1;
    }

    ShipDesign remove(int node) {
        if (node == 0) {
            return EMPTY;
        }
        Set<Integer> removedLinks = new HashSet<>();
        for (int i = 0; i < links.size(); i++) {
            if (links.get(i).other(node) >= 0) {
                removedLinks.add(i);
            }
        }
        return subset(tree(removedLinks).stream().map(Branch::node).toList(), removedLinks, BlockPos.ZERO);
    }

    ShipDesign withoutLinks(Set<Integer> detachedLinks) {
        List<Link> kept = new ArrayList<>();
        for (int index = 0; index < links.size(); index++) {
            if (!detachedLinks.contains(index)) {
                kept.add(links.get(index));
            }
        }
        return new ShipDesign(nodes, kept, stageCount);
    }

    ShipDesign subset(List<Integer> members, Set<Integer> detachedLinks, BlockPos offset) {
        return subset(members, detachedLinks, new PartPose(offset.multiply(4), PartPose.ROOT.rotation()), stageCount,
                IntUnaryOperator.identity());
    }

    ShipDesign subset(List<Integer> members, Set<Integer> cuts, PartPose frame, int count, IntUnaryOperator stageMap) {
        var keptNodes = new ArrayList<Node>(members.size());
        for (int member : members) {
            Node node = nodes.get(member);
            keptNodes.add(new Node(node.part(), node.pose().relativeTo(frame),
                    node.engineStage() < 0 ? -1 : stageMap.applyAsInt(node.engineStage()), node.hull()));
        }
        var keptLinks = remapLinks(links, indices(nodes.size(), members), cuts).stream()
                .map(link -> link.separationStage() < 0 ? link
                        : link.withStage(stageMap.applyAsInt(link.separationStage()))).toList();
        return new ShipDesign(keptNodes, keptLinks, count);
    }

    static int[] indices(int count, List<Integer> members) {
        int[] indices = new int[count];
        Arrays.fill(indices, -1);
        for (int i = 0; i < members.size(); i++) {
            indices[members.get(i)] = i;
        }
        return indices;
    }

    static List<Link> remapLinks(List<Link> links, int[] indices, Set<Integer> detachedLinks) {
        var keptLinks = new ArrayList<Link>();
        for (int index = 0; index < links.size(); index++) {
            Link link = links.get(index);
            int first = indices[link.first().node()];
            int second = indices[link.second().node()];
            if (first >= 0 && second >= 0 && !detachedLinks.contains(index)) {
                keptLinks.add(link.withNodes(first, second));
            }
        }
        return keptLinks;
    }

    ShipDesign moveEngine(int index, int stage) {
        var updated = new ArrayList<>(nodes);
        Node node = nodes.get(index);
        updated.set(index, new Node(node.part(), node.pose(), stage));
        return new ShipDesign(updated, links, stageCount);
    }

    ShipDesign moveSeparator(int index, int stage) {
        var updated = new ArrayList<>(links);
        Link link = links.get(index);
        updated.set(index, new Link(link.first(), link.second(), link.surface(), true, stage));
        return new ShipDesign(nodes, updated, stageCount);
    }

    public boolean lastStageEmpty() {
        int last = stageCount - 1;
        return nodes.stream().noneMatch(node -> node.engineStage() == last)
                && links.stream().noneMatch(link -> link.separationStage() == last);
    }
}
