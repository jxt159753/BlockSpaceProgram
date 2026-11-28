package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class DockingCapacityDesigns {
    public static final int MAX_PARTS = 288;
    public static final int MAX_HULLS = 108;
    public static final String NAME_PREFIX = "BSP140-";
    private static final Vec3 MODEL_CENTER = new Vec3(2, 2, 2);

    private DockingCapacityDesigns() {}

    public record Shape(int parts, int hulls, int blocks) {
        public String id() {
            return parts + "p_" + hulls + "h_" + blocks + "b";
        }
    }

    public record Pair(ShipDesign primary, ShipDesign incoming, int offset) {
        public ShipDesign merged() {
            var first = ShipDocking.openPorts(primary, BigInteger.ZERO, Set.of()).getFirst();
            var second = ShipDocking.openPorts(incoming, BigInteger.ZERO, Set.of()).getFirst();
            var frame = new PartPose(new BlockPos(offset * 4, 0, 0), OctahedralGroup.IDENTITY);
            var merged = ShipDocking.merge(primary, incoming, frame, first, second);
            if (merged == null || !merged.isValidLayout()) {
                throw new IllegalArgumentException("Invalid capacity ship design");
            }
            return merged;
        }
    }

    public static Pair pair(Shape shape) {
        int depth = shape.blocks() == 1_024 ? 32 : 64;
        int height = shape.blocks() / depth;
        var hullBlocks = new HashMap<BlockPos, BlockState>();
        for (int z = -depth / 2; z < depth / 2; z++) {
            for (int y = 0; y < height; y++) {
                hullBlocks.put(new BlockPos(0, y, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        var hull = HullRegion.capture(hullBlocks, BlockPos.ZERO);
        var geometry = new HashMap<BlockPos, BlockState>();
        for (BlockPos cell : BlockPos.betweenClosed(0, 0, 0, 3, 3, 3)) {
            geometry.put(cell.immutable(), (cell.getY() == 2 ? Blocks.CUT_COPPER : Blocks.SMOOTH_STONE).defaultBlockState());
        }
        var sample = BakedPart.bake(geometry).orElseThrow().withScale(0.25F);
        int primaryParts = (shape.parts() + 1) / 2;
        int primaryHulls = (shape.hulls() + 1) / 2;
        int width = width(primaryParts, primaryHulls);
        return new Pair(half(sample, hull, primaryParts, primaryHulls, width, height, Direction.EAST),
                half(sample, hull, shape.parts() - primaryParts, shape.hulls() - primaryHulls,
                        width(shape.parts() - primaryParts, shape.hulls() - primaryHulls), height, Direction.WEST),
                width);
    }

    private static int width(int parts, int hulls) {
        return Math.min(parts, Math.max((hulls + 1) / 2, (int) Math.ceil(Math.sqrt(parts))));
    }

    private static ShipDesign half(BakedPart sample, HullRegion hull, int parts, int hulls, int width,
            int spineHeight, Direction portFace) {
        var nodes = new ArrayList<ShipDesign.Node>();
        var links = new ArrayList<ShipDesign.Link>();
        for (int index = 0; index < parts; index++) {
            int x = index % width;
            int z = index / width;
            int portIndex = portFace == Direction.EAST ? width - 1 : 0;
            var connectors = index == portIndex ? List.of(new PartConnector(PartConnector.Kind.DOCKING,
                    MODEL_CENTER.relative(portFace, 2), portFace, 4, 4, 4)) : List.<PartConnector>of();
            var types = index == 0 ? Set.of(PartType.COMMAND_POD) : sample.types();
            var part = new BakedPart(sample.geometry(), sample.properties(), sample.centerOfMass(), sample.inertia(),
                    types, sample.scale(), connectors);
            nodes.add(new ShipDesign.Node(part,
                    new PartPose(new BlockPos(x * 4, spineHeight * 4, z * 4), OctahedralGroup.IDENTITY), -1));
            if (index > 0) {
                var face = x == 0 ? Direction.SOUTH : Direction.EAST;
                int parent = x == 0 ? index - width : index - 1;
                links.add(surface(parent, face, MODEL_CENTER.relative(face, 2), index,
                        MODEL_CENTER.relative(face.getOpposite(), 2)));
            }
        }
        for (int index = 0; index < hulls; index++) {
            int x = index / 2;
            boolean below = index % 2 == 0;
            var face = below ? Direction.UP : Direction.DOWN;
            int y = below ? 0 : spineHeight + 1;
            int hullIndex = nodes.size();
            nodes.add(ShipDesign.Node.hull(hull, new BlockPos(x, y, 0)));
            links.add(surface(hullIndex, face, new Vec3(0.5, below ? spineHeight : 0, 0.5), x,
                    MODEL_CENTER.relative(face.getOpposite(), 2)));
        }
        // Distinct stage blocks let undocking exercise membership bits beyond index 63.
        return new ShipDesign(nodes, links, 2);
    }

    private static ShipDesign.Link surface(int first, Direction face, Vec3 firstAnchor, int second, Vec3 secondAnchor) {
        return new ShipDesign.Link(new ShipDesign.Endpoint(first, -1, face, firstAnchor),
                new ShipDesign.Endpoint(second, -1, face.getOpposite(), secondAnchor), true, false, -1);
    }

    public static ShipDesign centerOnFirstPod(ShipDesign design) {
        var pod = BlockPos.containing(design.nodes().getFirst().pose().position());
        var nodes = IntStream.range(0, design.nodes().size()).boxed().toList();
        return design.subset(nodes, Set.of(), pod);
    }

    public static AssembledShipEntity spawn(ServerLevel level, ShipDesign design, Vec3 origin, String name,
            boolean benchmark) {
        var blueprint = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        blueprint.set(BlockSpaceProgram.FLIGHT_LAYOUT, design);
        var ship = AssembledShipEntity.spawn(level, blueprint, origin, OctahedralGroup.IDENTITY, null, benchmark);
        if (ship == null) {
            throw new IllegalStateException("No ship spawned for " + name);
        }
        ship.setCustomName(Component.literal(NAME_PREFIX + name));
        return ship;
    }

    public static boolean isTrialShip(AssembledShipEntity ship) {
        return ship.getCustomName() != null && ship.getCustomName().getString().startsWith(NAME_PREFIX);
    }

    public static void damageLastPart(AssembledShipEntity ship) {
        ship.activeFlight().damageCapacityPart(ship.design().partCount() - 1);
    }

    public static int highestDamageIndex(AssembledShipEntity ship) {
        return ship.damagedParts().bitLength() - 1;
    }

    public static int originCount(AssembledShipEntity ship) {
        return ship.dockingState().stages().size();
    }

    public static boolean release(AssembledShipEntity ship) {
        var links = ship.design().links();
        int joint = IntStream.range(0, links.size()).filter(index -> links.get(index).docking()).findFirst().orElseThrow();
        return ship.activeFlight().releaseDocking(joint);
    }
}
