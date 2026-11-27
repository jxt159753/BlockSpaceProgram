package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.fml.ModList;

public final class LaunchPadBlock extends Block {
    public LaunchPadBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(BlockSpaceProgram.SHIP_BLUEPRINT.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
            if (!player.getAbilities().mayBuild || !level.mayInteract(player, pos)) {
                return ItemInteractionResult.FAIL;
            }
            return launch(server, pos, serverPlayer, stack) ? ItemInteractionResult.CONSUME : ItemInteractionResult.FAIL;
        }
        return ItemInteractionResult.SUCCESS;
    }

    private static boolean launch(ServerLevel level, BlockPos pad, ServerPlayer player, ItemStack blueprint) {
        ShipDesign design = blueprint.get(BlockSpaceProgram.SHIP_DESIGN);
        if (design == null || !design.isValid()) {
            return reject(player, "blueprint");
        }
        boolean hasHull = design.hasHull();
        boolean hasSable = ModList.get().isLoaded("sable");
        if (hasHull && !hasSable) {
            return reject(player, "hull_requires_sable");
        }
        var rejection = ShipFacilityLimits.checkLaunch(player, design);
        if (rejection != null) {
            return reject(player, rejection);
        }
        LaunchPayment payment = LaunchPayment.plan(level, pad, player, design);
        if (payment.shortage() != null) {
            return reject(player, payment.shortage());
        }

        AABB bounds = design.bounds();
        Vec3 center = design.nodes().stream().filter(ShipDesign.Node::isCommandPod).findFirst().orElseThrow().bounds().getCenter();
        Vec3 offset = new Vec3(0.5 - center.x, 1 - bounds.minY, 0.5 - center.z);
        // A hull's blocks must line up with whole world cells. Round up to keep the lowest part above the pad.
        offset = hasHull ? new Vec3(Math.round(offset.x), Math.ceil(offset.y), Math.round(offset.z))
                : PartPose.fromQuarters(PartPose.snap(offset));
        Vec3 origin = Vec3.atLowerCornerOf(pad).add(offset);
        if (!hasHull && !isLoadedAndInBounds(level, bounds.move(origin))) {
            return reject(player, "blocked");
        }
        for (ShipDesign.Node node : design.nodes()) {
            if (node.hull() != null) {
                BlockPos hullOrigin = BlockPos.containing(origin).offset(node.hullOrigin());
                if (!node.hull().positionsMatch(pos -> isHullCellFree(level, hullOrigin.offset(pos)))) {
                    return reject(player, "blocked");
                }
                continue;
            }
            AABB box = node.bounds().move(origin);
            if ((hasHull && !isLoadedAndInBounds(level, box)) || !level.noCollision(box)
                    || !level.isUnobstructed(null, Shapes.create(box))
                    || (hasSable && SableShipFlight.obstructs(level, box))) {
                return reject(player, "blocked");
            }
        }

        AssembledShipEntity ship = AssembledShipEntity.spawn(level, blueprint, origin, OctahedralGroup.IDENTITY, player.getUUID());
        if (ship == null) {
            return reject(player, "spawn");
        }
        // Entity-join listeners run inline and may change inventory before the spawn returns.
        if (!payment.commit(player)) {
            ship.discard();
            return reject(player, "payment_changed");
        }
        player.displayClientMessage(Component.translatable("launch.blockspaceprogram.success"), true);
        return true;
    }

    private static boolean isLoadedAndInBounds(ServerLevel level, AABB bounds) {
        BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos max = BlockPos.containing(bounds.maxX - 0.125, bounds.maxY - 0.125, bounds.maxZ - 0.125);
        return bounds.minY >= level.getMinBuildHeight() && bounds.maxY <= level.getMaxBuildHeight()
                && level.getWorldBorder().isWithinBounds(bounds) && level.hasChunksAt(min, max);
    }

    private static boolean isHullCellFree(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos) || !level.isEmptyBlock(pos)) {
            return false;
        }
        AABB cell = new AABB(pos);
        return level.getWorldBorder().isWithinBounds(cell) && level.getEntities(null, cell).isEmpty()
                && !SableShipFlight.obstructs(level, cell);
    }

    private static boolean reject(ServerPlayer player, String reason) {
        return reject(player, Component.translatable("launch.blockspaceprogram." + reason));
    }

    private static boolean reject(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
        return false;
    }
}
