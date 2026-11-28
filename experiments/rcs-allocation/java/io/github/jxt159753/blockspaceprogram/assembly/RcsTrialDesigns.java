package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Vector3d;

final class RcsTrialDesigns {
    private RcsTrialDesigns() {}

    static ShipDesign shuttle(Direction port) {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 3; z++) {
                    blocks.put(new BlockPos(x, y, z), Blocks.IRON_BLOCK.defaultBlockState());
                }
            }
        }
        var panel = BlockSpaceProgram.DOCKING_INTERFACE.get().defaultBlockState().setValue(BlockStateProperties.FACING, port);
        blocks.put(new BlockPos(port == Direction.EAST ? 2 : 0, 1, 1), panel);
        var stack = BlockSpaceProgram.STACK_INTERFACE.get().defaultBlockState();
        blocks.put(new BlockPos(1, 0, 1), stack.setValue(BlockStateProperties.FACING, Direction.DOWN));
        var cabin = ratedPart(blocks, 1_400, 0, PartType.COMMAND_POD);
        var tank = ratedPart(Map.of(BlockPos.ZERO, stack.setValue(BlockStateProperties.FACING, Direction.UP)),
                150, 650, PartType.FUEL_TANK);
        var root = new ShipDesign(List.of(new ShipDesign.Node(cabin, PartPose.ROOT, -1)), List.of(), 1);
        int cabinMount = cabin.connectors().getFirst().normal() == Direction.DOWN ? 0 : 1;
        var anchor = cabin.connectors().get(cabinMount).center();
        var request = new ShipPlacement.Request(0, cabinMount, 0, Direction.DOWN, Direction.UP, anchor, 0, 1);
        var placed = ShipPlacement.place(root, tank, request);
        if (placed.error() != null || !placed.design().isValidLayout()) {
            throw new IllegalStateException("Cannot place the RCS fixture tank " + placed.error());
        }
        return placed.design();
    }

    static List<RcsFlightTrial.Mount> mounts(int count) {
        var mounts = new ArrayList<RcsFlightTrial.Mount>(count);
        for (int i = 0; i < count; i++) {
            int direction = i % 6;
            int pair = i / 6;
            double thrust = count == 12 ? 350 : 43 + (i * 13) % 19;
            double side = pair % 2 == 0 ? 1 : -1;
            double y = 0.85 + (count == 12 ? 0 : 0.04 * (pair % 4));
            double sign = direction % 2 == 0 ? 1 : -1;
            var point = switch (direction / 2) {
                case 0 -> new Vector3d(1.5 - sign * 1.5, y, 1.5 + side);
                case 1 -> new Vector3d(1.5 + side, 1 - sign, 1.5);
                default -> new Vector3d(1.5 + side, y, 1.5 - sign * 1.5);
            };
            var force = switch (direction / 2) {
                case 0 -> new Vector3d(sign * thrust, 0, 0);
                case 1 -> new Vector3d(0, sign * thrust, 0);
                default -> new Vector3d(0, 0, sign * thrust);
            };
            mounts.add(new RcsFlightTrial.Mount(0, point, force, 220, 0));
        }
        return mounts;
    }

    private static BakedPart ratedPart(Map<BlockPos, BlockState> blocks, double mass, double fuel, PartType type) {
        var part = BakedPart.bake(blocks).orElseThrow();
        var capacity = fuel > 0 ? Map.of(ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "fuel"), fuel)
                : Map.<ResourceLocation, Double>of();
        var ratings = new BlockPartProperties(mass, capacity, 0, 0, 0, 0, 0, 0, 0, 30);
        return new BakedPart(part.geometry(), ratings, part.centerOfMass(), part.inertia(), Set.of(type), 1, part.connectors());
    }
}
