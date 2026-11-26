package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

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
        Inventory inventory = player.getInventory();
        Map<BakedPart, Integer> required = new HashMap<>();
        for (ShipDesign.Node node : design.nodes()) {
            required.merge(node.part(), 1, Integer::sum);
        }
        List<Debit> payment = planPayment(inventory, required);
        if (payment == null) {
            return reject(player, "parts");
        }

        AABB bounds = design.bounds();
        Vec3 center = design.nodes().getFirst().bounds().getCenter();
        Vec3 offset = PartPose.fromQuarters(PartPose.snap(new Vec3(0.5 - center.x, 1 - bounds.minY, 0.5 - center.z)));
        Vec3 origin = Vec3.atLowerCornerOf(pad).add(offset);
        AABB worldBounds = bounds.move(origin);
        BlockPos min = BlockPos.containing(worldBounds.minX, worldBounds.minY, worldBounds.minZ);
        BlockPos max = BlockPos.containing(worldBounds.maxX - 0.125, worldBounds.maxY - 0.125, worldBounds.maxZ - 0.125);
        if (worldBounds.minY < level.getMinBuildHeight() || worldBounds.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(worldBounds) || !level.hasChunksAt(min, max)) {
            return reject(player, "blocked");
        }
        for (ShipDesign.Node node : design.nodes()) {
            AABB box = node.bounds().move(origin);
            if (!level.noCollision(box) || !level.isUnobstructed(null, Shapes.create(box))) {
                return reject(player, "blocked");
            }
        }

        AssembledShipEntity ship = new AssembledShipEntity(BlockSpaceProgram.ASSEMBLED_SHIP.get(), level);
        ship.setBlueprint(blueprint);
        ship.setPos(origin);
        if (!level.addFreshEntity(ship)) {
            return reject(player, "spawn");
        }
        // Entity-join listeners run inline and may change inventory before the spawn returns.
        if (payment.stream().anyMatch(debit -> !debit.stillAvailable(inventory))) {
            ship.discard();
            return reject(player, "parts");
        }
        payment.forEach(debit -> inventory.removeItem(debit.slot(), debit.count()));
        inventory.setChanged();
        player.displayClientMessage(Component.translatable("launch.blockspaceprogram.success"), true);
        return true;
    }

    @Nullable
    private static List<Debit> planPayment(Inventory inventory, Map<BakedPart, Integer> required) {
        Map<BakedPart, Integer> remaining = new HashMap<>(required);
        List<Debit> payment = new ArrayList<>();
        for (int slot = 0; slot <= Inventory.INVENTORY_SIZE && !remaining.isEmpty(); slot++) {
            int index = slot == Inventory.INVENTORY_SIZE ? Inventory.SLOT_OFFHAND : slot;
            BakedPart part = submittedPart(inventory.getItem(index));
            Integer needed = remaining.get(part);
            if (needed == null) {
                continue;
            }
            int count = Math.min(needed, inventory.getItem(index).getCount());
            payment.add(new Debit(index, part, count));
            if (count == needed) {
                remaining.remove(part);
            } else {
                remaining.put(part, needed - count);
            }
        }
        return remaining.isEmpty() ? payment : null;
    }

    @Nullable
    private static BakedPart submittedPart(ItemStack stack) {
        return stack.is(BlockSpaceProgram.BAKED_PART.get()) ? stack.get(BlockSpaceProgram.PART_DATA) : null;
    }

    private static boolean reject(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.translatable("launch.blockspaceprogram." + reason), true);
        return false;
    }

    private record Debit(int slot, BakedPart part, int count) {
        boolean stillAvailable(Inventory inventory) {
            ItemStack stack = inventory.getItem(slot);
            return stack.getCount() >= count && part.equals(submittedPart(stack));
        }
    }
}
