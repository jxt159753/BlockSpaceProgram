package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Endpoint;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Link;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Node;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

final class AeroTrialDesigns {
    private static final BlockState IRON = Blocks.IRON_BLOCK.defaultBlockState();
    private static final BlockState SEAT = Blocks.OAK_PLANKS.defaultBlockState();

    private AeroTrialDesigns() {}

    static ShipDesign capsule() {
        var layers = List.of(Blocks.RAW_IRON_BLOCK.defaultBlockState(), IRON, Blocks.IRON_ORE.defaultBlockState());
        var nodes = new ArrayList<Node>();
        var links = new ArrayList<Link>();
        for (int y = 0; y < layers.size(); y++) {
            var blocks = new LinkedHashMap<BlockPos, BlockState>();
            for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 2, 0, 2)) {
                blocks.put(pos.immutable(), layers.get(y));
            }
            nodes.add(new Node(bake(blocks), new PartPose(new BlockPos(0, 4 * y, 0), PartPose.ROOT.rotation()), -1));
            if (y > 0) {
                links.add(new Link(new Endpoint(y - 1, -1, Direction.UP, new Vec3(1.5, 1, 1.5)),
                        new Endpoint(y, -1, Direction.DOWN, new Vec3(1.5, 0, 1.5)), true, false, -1));
            }
        }
        return checked(new ShipDesign(nodes, links, 1));
    }

    static ShipDesign rocket() {
        var engine = Blocks.CUT_COPPER.defaultBlockState();
        var tank = Blocks.CAULDRON.defaultBlockState();
        var design = root(column(stack(Direction.DOWN), SEAT), 2);
        design = append(design, column(stack(Direction.DOWN), engine, tank, stack(Direction.UP)));
        design = append(design, column(separator(Direction.DOWN), stack(Direction.UP)));
        design = append(design, column(stack(Direction.DOWN), engine, tank, tank, stack(Direction.UP)));
        var nodes = new ArrayList<>(design.nodes());
        nodes.set(1, new Node(nodes.get(1).part(), nodes.get(1).pose(), 1));
        var links = new ArrayList<>(design.links());
        links.set(2, links.get(2).withStage(1));
        return checked(new ShipDesign(nodes, links, 2));
    }

    static ShipDesign payload() {
        var design = root(column(stack(Direction.DOWN), SEAT, IRON), 1);
        return append(design, bake(Map.of(BlockPos.ZERO, IRON, new BlockPos(1, 0, 0), stack(Direction.UP),
                new BlockPos(2, 0, 0), IRON)));
    }

    static ShipDesign fairings(ShipDesign payload) {
        var shell = column(separator(Direction.WEST), Blocks.SPRUCE_PLANKS.defaultBlockState());
        int marker = marker(shell, Direction.WEST);
        var result = ShipPlacement.place(payload, shell, new ShipPlacement.Request(0, -1, marker, Direction.EAST,
                Direction.WEST, new Vec3(1, 1.5, 0.5), 0, 2));
        if (result.error() != null || result.skippedMirrors() != 0 || result.design().nodes().size() != 4) {
            throw new IllegalStateException("Could not place both fairing halves: " + result.error());
        }
        return checked(result.design());
    }

    static ShipDesign benchmark() {
        var rootBlocks = markedCube(false);
        rootBlocks.put(new BlockPos(1, 1, 1), SEAT);
        var root = bake(rootBlocks);
        var body = bake(markedCube(false));
        var cut = bake(markedCube(true));
        var nodes = new ArrayList<Node>();
        var links = new ArrayList<Link>();
        for (int i = 0; i < ShipDesign.MAX_PARTS; i++) {
            nodes.add(new Node(i == 0 ? root : i == 17 ? cut : body,
                    new PartPose(new BlockPos(0, 12 * i, 0), PartPose.ROOT.rotation()), -1));
            if (i > 0) {
                links.add(new Link(port(nodes, i - 1, Direction.UP), port(nodes, i, Direction.DOWN), false, i == 18,
                        i == 18 ? 0 : -1));
            }
        }
        var hull = HullRegion.capture(Map.of(BlockPos.ZERO, IRON), BlockPos.ZERO);
        for (int i = 0; i < ShipDesign.MAX_HULL_NODES; i++) {
            int index = nodes.size();
            nodes.add(Node.hull(hull, new BlockPos(3, 3 * i + 1, 1)));
            links.add(new Link(new Endpoint(i, -1, Direction.EAST, new Vec3(3, 1.5, 1.5)),
                    new Endpoint(index, -1, Direction.WEST, new Vec3(0, 0.5, 0.5)), true, false, -1));
        }
        return checked(new ShipDesign(nodes, links, 1));
    }

    static Map<BlockPos, BlockState> cube(int width, BlockState state) {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, width - 1, width - 1, width - 1)) {
            blocks.put(pos.immutable(), state);
        }
        return blocks;
    }

    static BakedPart bake(Map<BlockPos, BlockState> blocks) {
        return BakedPart.bake(blocks).orElseThrow();
    }

    private static Map<BlockPos, BlockState> markedCube(boolean cut) {
        var blocks = cube(3, IRON);
        blocks.put(new BlockPos(1, 0, 1), stack(Direction.DOWN));
        blocks.put(new BlockPos(1, 2, 1), cut ? separator(Direction.UP) : stack(Direction.UP));
        return blocks;
    }

    private static ShipDesign root(BakedPart part, int stages) {
        return new ShipDesign(List.of(new Node(part, PartPose.ROOT, -1)), List.of(), stages);
    }

    private static ShipDesign append(ShipDesign design, BakedPart part) {
        int tail = design.nodes().size() - 1;
        var target = design.nodes().getLast();
        var result = ShipPlacement.place(design, part,
                new ShipPlacement.Request(tail, marker(target.part(), Direction.DOWN), marker(part, Direction.UP),
                        Direction.DOWN, Direction.UP, target.bounds().getCenter(), 0, 1));
        if (result.error() != null) {
            throw new IllegalStateException("Could not stack fixture part: " + result.error());
        }
        return result.design();
    }

    private static Endpoint port(List<Node> nodes, int index, Direction direction) {
        var part = nodes.get(index).part();
        int marker = marker(part, direction);
        return new Endpoint(index, marker, direction, part.connectors().get(marker).center());
    }

    private static int marker(BakedPart part, Direction direction) {
        for (int index = 0; index < part.connectors().size(); index++) {
            if (part.connectors().get(index).normal() == direction) {
                return index;
            }
        }
        throw new IllegalStateException("Missing fixture connector " + direction);
    }

    private static BakedPart column(BlockState... states) {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (int y = 0; y < states.length; y++) {
            blocks.put(new BlockPos(0, y, 0), states[y]);
        }
        return bake(blocks);
    }

    private static BlockState stack(Direction side) {
        return BlockSpaceProgram.STACK_INTERFACE.get().defaultBlockState().setValue(BlockStateProperties.FACING, side);
    }

    private static BlockState separator(Direction side) {
        return BlockSpaceProgram.SEPARATION_INTERFACE.get().defaultBlockState().setValue(BlockStateProperties.FACING, side);
    }

    private static ShipDesign checked(ShipDesign design) {
        if (!design.isValidLayout()) {
            throw new IllegalStateException("Invalid aerodynamic fixture of " + design.nodes().size() + " nodes");
        }
        return design;
    }
}
