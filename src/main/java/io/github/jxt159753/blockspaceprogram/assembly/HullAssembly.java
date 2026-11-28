package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.BakingTableBlock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class HullAssembly {
    private static final TagKey<Block> HULL_BOUNDARY = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "hull_boundary"));

    private HullAssembly() {}

    @Nullable
    static Component assemble(ServerPlayer player, BlockPos start) {
        var level = player.serverLevel();
        if (!player.getAbilities().mayBuild || player.isSpectator() || !level.mayInteract(player, start)) {
            return message("world_permission");
        }
        var structure = new LinkedHashMap<BlockPos, BlockState>();
        Component rejection = collect(level, start, structure);
        if (rejection != null) {
            return rejection;
        }

        HullRegion region;
        try {
            region = HullRegion.capture(structure, start);
        } catch (IllegalStateException error) {
            return message("hull_mass");
        }
        Vec3 origin = Vec3.atLowerCornerOf(start);
        AABB bounds = region.bounds().move(origin);
        var data = WorldAssembly.get(level);
        var contacts = new LinkedHashMap<PlacedPartEntity, Contact>();
        var groups = new LinkedHashMap<WorldAssembly.Group, List<PlacedPartEntity>>();
        // The query must include the origins of parts whose bounds reach the hull.
        for (var part : level.getEntitiesOfClass(PlacedPartEntity.class, bounds.inflate(BakedPart.MAX_SIDE),
                PlacedPartEntity::isPickable)) {
            AABB box = part.getBoundingBox();
            if (sharesCell(box, structure)) {
                return message("hull_collision");
            }
            var contact = contact(level, box, structure);
            if (contact != null) {
                contacts.put(part, contact);
                groups.putIfAbsent(data.group(part), new ArrayList<>());
            }
        }
        int partCount = 0;
        for (var group : groups.entrySet()) {
            partCount += group.getKey().members().size();
            if (partCount > ShipDesign.MAX_PARTS) {
                return message("part_limit");
            }
            for (var member : group.getKey().members()) {
                if (!(level.getEntity(member.id()) instanceof PlacedPartEntity part) || !part.isPickable()) {
                    return message("world_group");
                }
                if (sharesCell(part.getBoundingBox(), structure)) {
                    return message("hull_collision");
                }
                group.getValue().add(part);
            }
        }
        var parts = new ArrayList<>(groups.values().stream().flatMap(List::stream).toList());
        var pod = parts.stream().filter(PlacedPartEntity::isCommandPod).findFirst().orElse(null);
        if (pod == null) {
            return message("root");
        }
        parts.addFirst(parts.remove(parts.indexOf(pod)));
        var design = design(region, origin, contacts, groups, parts);
        if (!design.isValid()) {
            return message("world_group");
        }
        rejection = ShipFacilityLimits.checkLaunch(player, design);
        if (rejection != null) {
            return rejection;
        }
        Set<PlacedPartEntity> replaced = Set.copyOf(parts);
        for (var part : parts) {
            AABB box = part.getBoundingBox();
            if (!WorldPartPlacement.mayBuildIn(player, box)) {
                return message("world_permission");
            }
            String error = WorldPartPlacement.obstruction(level, box, replaced);
            if (error != null) {
                return message(error);
            }
        }
        for (var block : structure.entrySet()) {
            if (!BakingTableBlock.canRemoveBlock(level, player, block.getKey(), block.getValue())) {
                return message("world_permission");
            }
        }

        var stack = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        stack.set(BlockSpaceProgram.FLIGHT_LAYOUT, design);
        var ship = AssembledShipEntity.spawn(level, stack, origin, OctahedralGroup.IDENTITY, player.getUUID());
        if (ship == null) {
            return message("world_spawn");
        }
        // Entity join handlers run before the original blocks or parts are consumed.
        for (var group : groups.entrySet()) {
            for (int i = 0; i < group.getValue().size(); i++) {
                var part = group.getValue().get(i);
                var member = group.getKey().members().get(i);
                if (!part.isPickable() || !data.group(part).equals(group.getKey())
                        || !node(part, origin, member).equals(design.nodes().get(parts.indexOf(part)))) {
                    ship.discard();
                    return message("world_group");
                }
            }
        }
        if (!BakingTableBlock.removeStructure(level, structure)) {
            ship.discard();
            return message("hull_changed");
        }
        data.consume(level, groups.keySet(), parts);
        return null;
    }

    @Nullable
    private static Component collect(ServerLevel level, BlockPos start, Map<BlockPos, BlockState> structure) {
        if (!level.hasChunkAt(start) || !level.getWorldBorder().isWithinBounds(start)) {
            return message("world_bounds");
        }
        var first = level.getBlockState(start);
        if (first.isAir() || first.is(HULL_BOUNDARY)) {
            return message("hull_start");
        }
        if (!BakingTableBlock.canCopyBlock(level, start, first)) {
            return unsupported(first);
        }
        int limit = ShipServerConfig.MAX_HULL_BLOCKS.get();
        var seen = new HashSet<BlockPos>();
        var pending = new ArrayDeque<BlockPos>();
        pending.add(start);
        seen.add(start);
        structure.put(start, first);
        BlockState unsupported = null;
        while (!pending.isEmpty()) {
            BlockPos pos = pending.remove();
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (!level.isInWorldBounds(next) || !seen.add(next)) {
                    continue;
                }
                if (!level.hasChunkAt(next) || !level.getWorldBorder().isWithinBounds(next)) {
                    return message("world_bounds");
                }
                var state = level.getBlockState(next);
                if (state.isAir() || state.is(HULL_BOUNDARY)) {
                    continue;
                }
                if (!BakingTableBlock.canCopyBlock(level, next, state)) {
                    if (unsupported == null) {
                        unsupported = state;
                    }
                    continue;
                }
                structure.put(next, state);
                if (structure.size() > limit) {
                    return message("hull_too_large", limit);
                }
                pending.add(next);
            }
        }
        return unsupported == null ? null : unsupported(unsupported);
    }

    private static boolean sharesCell(AABB box, Map<BlockPos, BlockState> structure) {
        for (BlockPos pos : WorldPartPlacement.coveredBlocks(box)) {
            if (structure.containsKey(pos)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Contact contact(ServerLevel level, AABB box, Map<BlockPos, BlockState> structure) {
        for (BlockPos cell : WorldPartPlacement.coveredBlocks(box)) {
            for (Direction normal : Direction.values()) {
                BlockPos block = cell.relative(normal.getOpposite());
                var state = structure.get(block);
                if (state != null && ShipPlacement.coversHullFace(box, block, normal)
                        && Block.isFaceFull(state.getCollisionShape(level, block), normal)) {
                    return new Contact(Vec3.atCenterOf(block).relative(normal, 0.5), normal);
                }
            }
        }
        return null;
    }

    private static ShipDesign design(HullRegion region, Vec3 origin, Map<PlacedPartEntity, Contact> contacts,
            Map<WorldAssembly.Group, List<PlacedPartEntity>> groups, List<PlacedPartEntity> parts) {
        // The hull uses the world's whole-block grid. The root pod can sit on the quarter-block grid.
        var nodes = new ArrayList<ShipDesign.Node>(java.util.Collections.nCopies(parts.size(), null));
        var links = new ArrayList<ShipDesign.Link>();
        int stages = 1;
        for (var group : groups.entrySet()) {
            var members = group.getValue();
            int[] indices = new int[members.size()];
            for (int i = 0; i < members.size(); i++) {
                var part = members.get(i);
                int index = parts.indexOf(part);
                indices[i] = index;
                nodes.set(index, node(part, origin, group.getKey().members().get(i)));
            }
            links.addAll(ShipDesign.remapLinks(group.getKey().links(), indices, Set.of()));
            stages = Math.max(stages, group.getKey().stageCount());
        }
        int hullIndex = nodes.size();
        nodes.add(ShipDesign.Node.hull(region, BlockPos.ZERO));
        for (var contact : contacts.entrySet()) {
            int index = parts.indexOf(contact.getKey());
            var node = nodes.get(index);
            Direction normal = contact.getValue().normal();
            Vec3 anchor = contact.getValue().point().subtract(origin);
            var hull = new ShipDesign.Endpoint(hullIndex, -1, normal, anchor);
            var attached = new ShipDesign.Endpoint(index, -1, node.pose().localDirection(normal.getOpposite()),
                    node.pose().localPoint(anchor).scale(1 / node.part().scale()));
            links.add(new ShipDesign.Link(hull, attached, true, false, -1));
        }
        return new ShipDesign(nodes, links, stages);
    }

    private static ShipDesign.Node node(PlacedPartEntity part, Vec3 origin, WorldAssembly.Member member) {
        return member.node(part, new PartPose(PartPose.snap(part.position().subtract(origin)), part.rotation()));
    }

    private static Component unsupported(BlockState state) {
        return message("hull_unsupported", state.getBlock().getName());
    }

    private static Component message(String key, Object... arguments) {
        return Component.translatable("assembly.blockspaceprogram." + key, arguments);
    }

    private record Contact(Vec3 point, Direction normal) {}
}
