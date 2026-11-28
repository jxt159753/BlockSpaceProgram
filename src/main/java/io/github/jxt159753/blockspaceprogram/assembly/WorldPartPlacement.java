package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

public final class WorldPartPlacement {
    private WorldPartPlacement() {}

    public record Options(boolean surface, int source, int turns, int symmetry) {}

    public record Placement(Vec3 origin, OctahedralGroup rotation, AABB bounds, @Nullable ShipDesign.Link link) {}

    public record Preview(List<Placement> placements, @Nullable String error) {}

    public static Preview onBlock(Player player, ItemStack stack, BakedPart part, BlockPos clicked, Direction face) {
        AABB local = part.localBounds();
        Vec3 centered = new Vec3(
                clicked.getX() + (1 - local.getXsize()) / 2 + face.getStepX() * (1 + local.getXsize()) / 2,
                clicked.getY() + (face == Direction.UP ? 1 : face == Direction.DOWN ? -local.getYsize() : 0),
                clicked.getZ() + (1 - local.getZsize()) / 2 + face.getStepZ() * (1 + local.getZsize()) / 2);
        Vec3 origin = PartPose.fromQuarters(PartPose.snap(centered));
        AABB bounds = local.move(origin);
        var placement = new Placement(origin, OctahedralGroup.IDENTITY, bounds, null);
        BlockPos adjacent = clicked.relative(face);
        if (!player.mayUseItemAt(adjacent, face, stack) || !mayBuildIn(player, bounds)) {
            return new Preview(List.of(placement), "world_permission");
        }
        return new Preview(List.of(placement), obstruction(player.level(), bounds, Set.of()));
    }

    static InteractionResult placeOnBlock(UseOnContext context, Player player, BakedPart part) {
        Preview preview = onBlock(player, context.getItemInHand(), part, context.getClickedPos(), context.getClickedFace());
        if (preview.error() != null) {
            if (!context.getLevel().isClientSide) {
                report(player, preview.error());
            }
            return InteractionResult.FAIL;
        }
        if (context.getLevel() instanceof ServerLevel level) {
            var placed = spawnPart(level, player, context.getHand(), part, preview.placements().getFirst());
            if (placed == null) {
                return InteractionResult.FAIL;
            }
            takePart(player, context.getHand());
            if (ExhaustWarning.blocked(placed)) {
                report(player, ExhaustWarning.MESSAGE);
            }
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    public static Preview attached(Player player, PlacedPartEntity target, BakedPart part, Vec3 hit, Options options) {
        BakedPart targetPart = target.part();
        if (targetPart == null || options.turns() < 0 || options.turns() > 3) {
            return new Preview(List.of(), "target");
        }
        Vec3 eye = player.getEyePosition();
        Vec3 aim = hit.subtract(eye);
        double distance = aim.length();
        double reach = player.entityInteractionRange();
        if (!(distance > 0 && distance <= reach)) {
            return new Preview(List.of(), "target");
        }
        Vec3 start = eye.subtract(target.position());
        Vec3 end = start.add(aim.scale((reach + 0.001) / distance));
        var targetNode = ShipDesign.Node.placed(targetPart, new PartPose(BlockPos.ZERO, target.rotation()));
        var surface = AABB.clip(List.of(targetNode.bounds()), start, end, BlockPos.ZERO);
        if (surface == null) {
            return new Preview(List.of(), "target");
        }
        MarkerHit marker = options.surface() ? null : pickMarker(targetNode, start, end);
        Vec3 contact = marker == null ? surface.getLocation() : marker.position();
        if (player.level().clip(new ClipContext(eye, contact.add(target.position()), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) {
            return new Preview(List.of(), "target");
        }

        int targetIndex = marker == null ? -1 : marker.index();
        PartConnector port = marker == null ? null : targetPart.connectors().get(targetIndex);
        Direction normal = port == null ? surface.getDirection() : target.rotation().rotate(port.normal());
        var sources = new ArrayList<>(ShipPlacement.sources(part, port != null));
        if (sources.isEmpty()) {
            return new Preview(List.of(), "source");
        }
        sources.sort(Comparator.comparing((ShipPlacement.Source source) -> port != null
                        && !port.matchesStackSize(part.connectors().get(source.marker())))
                .thenComparing(source -> source.normal() != normal.getOpposite()));
        var source = sources.get(Math.floorMod(options.source(), sources.size()));
        var request = new ShipPlacement.Request(0, targetIndex, source.marker(), normal, source.normal(),
                contact, options.turns(), port == null ? options.symmetry() : 1);
        var result = ShipPlacement.place(new ShipDesign(List.of(targetNode), List.of(), 1), part, request);
        if (result.error() != null) {
            var candidate = result.candidate();
            return new Preview(candidate == null ? List.of() : List.of(placement(target, candidate, null)), result.error());
        }
        var placements = result.design().links().stream()
                .map(link -> placement(target, result.design().nodes().get(link.second().node()), link)).toList();
        if (targetIndex >= 0 && target.isMarkerOccupied(targetIndex)) {
            return new Preview(placements, "world_occupied");
        }
        if (result.skippedMirrors() > 0) {
            return new Preview(placements, "world_symmetry_fit");
        }
        if (target.groupSize() + placements.size() > ShipDesign.MAX_PARTS) {
            return new Preview(placements, "part_limit");
        }
        if (!WorldAssembly.canUse(player, target)) {
            return new Preview(placements, "world_permission");
        }
        for (var placement : placements) {
            if (!mayBuildIn(player, placement.bounds())) {
                return new Preview(placements, "world_permission");
            }
            String error = obstruction(player.level(), placement.bounds(), Set.of());
            if (error != null) {
                return new Preview(placements, error);
            }
        }
        return new Preview(placements, null);
    }

    private static Placement placement(PlacedPartEntity target, ShipDesign.Node node, @Nullable ShipDesign.Link link) {
        return new Placement(target.position().add(node.pose().position()), node.pose().rotation(),
                node.bounds().move(target.position()), link);
    }

    @Nullable
    private static MarkerHit pickMarker(ShipDesign.Node target, Vec3 start, Vec3 end) {
        Vec3 ray = end.subtract(start);
        MarkerHit nearest = null;
        double distance = 1;
        for (int i = 0; i < target.part().connectors().size(); i++) {
            PartConnector connector = target.part().connectors().get(i);
            if (!connector.kind().isStack()) {
                continue;
            }
            Vec3 outward = Vec3.atLowerCornerOf(target.pose().rotation().rotate(connector.normal()).getNormal());
            double speed = ray.dot(outward);
            if (speed >= 0) {
                continue;
            }
            double time = target.point(connector.center()).subtract(start).dot(outward) / speed;
            if (time >= 0 && time < distance) {
                Vec3 point = start.add(ray.scale(time));
                if (target.transform(connector.modelFace()).distanceToSqr(point) < 1.0E-8) {
                    distance = time;
                    nearest = new MarkerHit(i, point);
                }
            }
        }
        return nearest;
    }

    static void placeAttached(ServerPlayer player, PlacedPartEntity target, InteractionHand hand, Vec3 hit, Options options) {
        ItemStack stack = player.getItemInHand(hand);
        BakedPart part = stack.is(BlockSpaceProgram.BAKED_PART.get()) ? stack.get(BlockSpaceProgram.PART_DATA) : null;
        if (part == null || player.isSecondaryUseActive() || !WorldAssembly.canUse(player, target)) {
            return;
        }
        Preview preview = attached(player, target, part, hit, options);
        if (preview.error() != null) {
            report(player, preview.error());
            return;
        }
        ServerLevel level = player.serverLevel();
        WorldAssembly data = WorldAssembly.get(level);
        var group = data.group(target);
        var placements = preview.placements();
        ItemStack sample = stack.copyWithCount(1);
        if (!payForPlacement(player, hand, sample, placements.size(), false)) {
            report(player, "world_symmetry_parts", placements.size());
            return;
        }
        var children = new ArrayList<PlacedPartEntity>(placements.size());
        for (var placement : placements) {
            var child = spawnPart(level, player, hand, part, placement);
            if (child == null) {
                children.forEach(PlacedPartEntity::discard);
                return;
            }
            children.add(child);
        }
        // Entity-join listeners may change the group or inventory during placement.
        if (!target.isPickable() || !group.equals(data.group(target)) || children.stream().anyMatch(child -> !child.isPickable())) {
            children.forEach(PlacedPartEntity::discard);
            report(player, "world_group");
            return;
        }
        if (!payForPlacement(player, hand, sample, placements.size(), true)) {
            children.forEach(PlacedPartEntity::discard);
            report(player, "world_symmetry_parts", placements.size());
            return;
        }
        data.connect(level, group, target, children, placements.stream().map(Placement::link).toList());
        if (children.stream().anyMatch(ExhaustWarning::blocked)) {
            report(player, ExhaustWarning.MESSAGE);
        }
    }

    private static boolean payForPlacement(Player player, InteractionHand hand, ItemStack sample, int count, boolean consume) {
        ItemStack held = player.getItemInHand(hand);
        if (!ItemStack.isSameItemSameComponents(sample, held)) {
            return false;
        }
        if (player.hasInfiniteMaterials()) {
            return true;
        }
        var inventory = player.getInventory();
        int heldSlot = hand == InteractionHand.MAIN_HAND ? inventory.selected : Inventory.SLOT_OFFHAND;
        var matching = new ArrayList<ItemStack>();
        matching.add(held);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (slot != heldSlot && ItemStack.isSameItemSameComponents(sample, stack)) {
                matching.add(stack);
            }
        }
        if (hand == InteractionHand.MAIN_HAND && ItemStack.isSameItemSameComponents(sample, player.getOffhandItem())) {
            matching.add(player.getOffhandItem());
        }
        if (matching.stream().mapToInt(ItemStack::getCount).sum() < count) {
            return false;
        }
        if (consume) {
            for (ItemStack stack : matching) {
                int used = Math.min(stack.getCount(), count);
                stack.shrink(used);
                count -= used;
                if (count == 0) {
                    break;
                }
            }
        }
        return true;
    }

    @Nullable
    private static PlacedPartEntity spawnPart(ServerLevel level, Player player, InteractionHand hand,
            BakedPart part, Placement placement) {
        var entity = PlacedPartEntity.create(level, player.getItemInHand(hand), placement.origin(), placement.rotation());
        if (!level.addFreshEntity(entity)) {
            report(player, "world_part_spawn");
            return null;
        }
        if (!entity.isPickable() || !part.equals(player.getItemInHand(hand).get(BlockSpaceProgram.PART_DATA))) {
            entity.discard();
            return null;
        }
        return entity;
    }

    private static void takePart(Player player, InteractionHand hand) {
        if (!player.hasInfiniteMaterials()) {
            player.getItemInHand(hand).shrink(1);
        }
    }

    static boolean mayBuildIn(Player player, AABB box) {
        for (BlockPos pos : coveredBlocks(box)) {
            if (!player.level().mayInteract(player, pos)) {
                return false;
            }
        }
        return true;
    }

    static Iterable<BlockPos> coveredBlocks(AABB box) {
        return BlockPos.betweenClosed(minBlock(box), maxBlock(box));
    }

    @Nullable
    static String obstruction(Level level, AABB box, Set<? extends Entity> excluded) {
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(box) || !level.hasChunksAt(minBlock(box), maxBlock(box))) {
            return "world_bounds";
        }
        if (!level.noBlockCollision(null, box)) {
            return "world_terrain";
        }
        // Entities are indexed by their position; a part's collider can extend nine blocks from that corner.
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box.inflate(BakedPart.MAX_SIDE), other ->
                !other.isRemoved() && !other.isSpectator() && !excluded.contains(other) && other.getBoundingBox().intersects(box))) {
            if (entity instanceof PlacedPartEntity part && part.isPickable()) {
                return "world_part";
            }
            if (entity instanceof LivingEntity || entity.blocksBuilding || entity.canBeCollidedWith()) {
                return "world_entity";
            }
        }
        return ModList.get().isLoaded("sable") && SableShipFlight.obstructs(level, box) ? "world_entity" : null;
    }

    private static BlockPos minBlock(AABB box) {
        return BlockPos.containing(box.minX, box.minY, box.minZ);
    }

    private static BlockPos maxBlock(AABB box) {
        return BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ));
    }

    private static void report(Player player, String error, Object... args) {
        player.displayClientMessage(Component.translatable("assembly.blockspaceprogram." + error, args), true);
    }

    private record MarkerHit(int index, Vec3 position) {}
}
