package io.github.jxt159753.blockspaceprogram.part;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.CommonHooks;

public final class BakingTableBlock extends Block {
    private static final int SCAN_RADIUS = BakedPart.MAX_SIDE / 2;
    private static final int BATCH_UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public BakingTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        boolean baking = stack.is(Items.COPPER_INGOT);
        BakedPart part = stack.is(BlockSpaceProgram.BAKED_PART.get()) ? stack.get(BlockSpaceProgram.PART_DATA) : null;
        if (!baking && part == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
            if (!serverPlayer.getAbilities().mayBuild || !server.mayInteract(serverPlayer, pos)) {
                return ItemInteractionResult.FAIL;
            }
            if (baking && !bake(server, pos, serverPlayer, stack)) {
                return ItemInteractionResult.FAIL;
            }
            if (part != null) {
                dismantle(serverPlayer, stack, part);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    private static boolean bake(ServerLevel level, BlockPos table, ServerPlayer player, ItemStack material) {
        Map<BlockPos, BlockState> structure = scan(level, table, player);
        if (structure == null) {
            return false;
        }
        BakedPart part = BakedPart.bake(structure).orElse(null);
        if (part == null) {
            return reject(player, "invalid_properties");
        }

        for (var entry : structure.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.mayInteract(player, pos)
                    || CommonHooks.fireBlockBreak(level, player.gameMode.getGameModeForPlayer(), player, pos, entry.getValue()).isCanceled()) {
                return reject(player, "protected");
            }
        }
        if (!removeStructure(level, structure)) {
            return reject(player, "changed");
        }

        material.shrink(1);
        ItemStack result = new ItemStack(BlockSpaceProgram.BAKED_PART.get());
        result.set(BlockSpaceProgram.PART_DATA, part);
        player.getInventory().placeItemBackInInventory(result);
        return true;
    }

    @Nullable
    private static Map<BlockPos, BlockState> scan(ServerLevel level, BlockPos table, ServerPlayer player) {
        BlockPos min = table.offset(-SCAN_RADIUS, 1, -SCAN_RADIUS);
        BlockPos max = table.offset(SCAN_RADIUS, BakedPart.MAX_SIDE, SCAN_RADIUS);
        if (!level.isInWorldBounds(min) || !level.isInWorldBounds(max)
                || !level.hasChunksAt(min.offset(-1, 0, -1), max.offset(1, 0, 1))) {
            reject(player, "unavailable");
            return null;
        }

        BlockPos start = table.above();
        BlockState first = level.getBlockState(start);
        if (first.isAir()) {
            reject(player, "missing_anchor");
            return null;
        }
        BoundingBox bounds = BoundingBox.fromCorners(min, max);
        Map<BlockPos, BlockState> structure = new LinkedHashMap<>();
        Queue<BlockPos> pending = new ArrayDeque<>();
        structure.put(start, first);
        pending.add(start);
        while (!pending.isEmpty()) {
            BlockPos pos = pending.remove();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = pos.relative(direction);
                if (neighbor.equals(table) || structure.containsKey(neighbor)) {
                    continue;
                }
                BlockState state = level.getBlockState(neighbor);
                if (state.isAir()) {
                    continue;
                }
                if (!bounds.isInside(neighbor)) {
                    reject(player, "too_large");
                    return null;
                }
                structure.put(neighbor, state);
                pending.add(neighbor);
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!structure.containsKey(pos) && !level.getBlockState(pos).isAir()) {
                reject(player, "disconnected");
                return null;
            }
        }
        for (var entry : structure.entrySet()) {
            BlockState state = entry.getValue();
            if (state.hasBlockEntity() || !state.getFluidState().isEmpty()
                    || state.getDestroySpeed(level, entry.getKey()) < 0
                    || !(state.getBlock().asItem() instanceof BlockItem item) || item.getBlock() != state.getBlock()) {
                reject(player, "unsupported", state.getBlock().getName());
                return null;
            }
        }
        return structure;
    }

    private static boolean removeStructure(ServerLevel level, Map<BlockPos, BlockState> structure) {
        // Removing a support must not drop other members of the same batch as loot.
        List<Map.Entry<BlockPos, BlockState>> removed = new ArrayList<>(structure.size());
        for (var entry : structure.entrySet()) {
            BlockPos pos = entry.getKey();
            if (level.getBlockState(pos) != entry.getValue()
                    || !level.setBlock(pos, Blocks.AIR.defaultBlockState(), BATCH_UPDATE_FLAGS)) {
                for (var restore : removed) {
                    level.setBlock(restore.getKey(), restore.getValue(), BATCH_UPDATE_FLAGS);
                }
                return false;
            }
            removed.add(entry);
        }
        structure.forEach((pos, state) -> level.blockUpdated(pos, state.getBlock()));
        return true;
    }

    private static void dismantle(ServerPlayer player, ItemStack stack, BakedPart part) {
        Map<Item, Integer> returned = new LinkedHashMap<>();
        for (BakedPart.StructureBlock block : part.blocks()) {
            BlockState state = block.state();
            if (state.getOptionalValue(BlockStateProperties.DOUBLE_BLOCK_HALF).orElse(null) == DoubleBlockHalf.UPPER
                    || state.getOptionalValue(BlockStateProperties.BED_PART).orElse(null) == BedPart.HEAD) {
                continue;
            }
            returned.merge(state.getBlock().asItem(), 1, Integer::sum);
        }

        stack.shrink(1);
        returned.forEach((item, count) -> {
            int remaining = count;
            while (remaining > 0) {
                ItemStack result = item.getDefaultInstance();
                result.setCount(Math.min(remaining, result.getMaxStackSize()));
                remaining -= result.getCount();
                player.getInventory().placeItemBackInInventory(result);
            }
        });
    }

    private static boolean reject(ServerPlayer player, String reason, Object... args) {
        player.displayClientMessage(Component.translatable("block.blockspaceprogram.baking_table." + reason, args), true);
        return false;
    }
}
