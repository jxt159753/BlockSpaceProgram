package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.Nullable;

final class LaunchPayment {
    private static final List<IntegerProperty> STACKED_BLOCK_COUNTS = List.of(BlockStateProperties.CANDLES,
            BlockStateProperties.PICKLES, BlockStateProperties.LAYERS, BlockStateProperties.EGGS, BlockStateProperties.FLOWER_AMOUNT);

    private final List<Debit> debits;
    @Nullable
    private final Component shortage;

    private LaunchPayment(List<Debit> debits, @Nullable Component shortage) {
        this.debits = debits;
        this.shortage = shortage;
    }

    static LaunchPayment plan(ServerLevel level, BlockPos pad, ServerPlayer player, ShipDesign design) {
        Map<Material, Integer> remaining = new LinkedHashMap<>();
        for (ShipDesign.Node node : design.nodes()) {
            if (node.part() != null) {
                remaining.merge(new Material(BlockSpaceProgram.BAKED_PART.get(), node.part()), 1, Integer::sum);
            } else {
                node.hull().forEach((pos, material) -> {
                    int count = blockItemCount(material.state());
                    if (count > 0) {
                        remaining.merge(new Material(material.state().getBlock().asItem(), null), count, Integer::sum);
                    }
                });
            }
        }

        var debits = new ArrayList<Debit>();
        var inventory = new Source(new InvWrapper(player.getInventory()), () -> true);
        planSlots(inventory, 0, Inventory.INVENTORY_SIZE, remaining, debits);
        planSlots(inventory, Inventory.SLOT_OFFHAND, Inventory.SLOT_OFFHAND + 1, remaining, debits);

        var seen = new HashSet<IItemHandler>();
        for (Direction direction : Direction.values()) {
            if (remaining.isEmpty()) {
                break;
            }
            BlockPos pos = pad.relative(direction);
            if (!canUseContainer(level, player, pos)) {
                continue;
            }
            var cache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, pos, direction.getOpposite());
            IItemHandler handler = cache.getCapability();
            if (handler != null && seen.add(handler)) {
                var source = new Source(handler, () -> canUseContainer(level, player, pos) && cache.getCapability() == handler);
                planSlots(source, 0, handler.getSlots(), remaining, debits);
            }
        }
        if (remaining.isEmpty()) {
            return new LaunchPayment(debits, null);
        }
        var missing = remaining.entrySet().iterator().next();
        return new LaunchPayment(List.of(), Component.translatable("launch.blockspaceprogram.missing_material",
                missing.getKey().item().getDescription(), missing.getValue()));
    }

    private static boolean canUseContainer(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.mayInteract(player, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        // The chest capability exposes both halves of a double chest.
        return !(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                || level.mayInteract(player, pos.relative(ChestBlock.getConnectedDirection(state)));
    }

    private static void planSlots(Source source, int start, int end, Map<Material, Integer> remaining, List<Debit> debits) {
        var handler = source.handler();
        for (int slot = start; slot < end && !remaining.isEmpty(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            var material = new Material(item, stack.is(BlockSpaceProgram.BAKED_PART.get()) ? stack.get(BlockSpaceProgram.PART_DATA) : null);
            Integer needed = remaining.get(material);
            if (needed == null) {
                continue;
            }
            int wanted = Math.min(needed, stack.getCount());
            int request = Math.min(wanted, stack.getMaxStackSize());
            ItemStack simulated = handler.extractItem(slot, request, true);
            if (simulated.isEmpty() || !ItemStack.isSameItemSameComponents(stack, simulated)) {
                continue;
            }
            // A bulk slot can hold more than the maximum stack size returned by one extraction.
            int count = simulated.getCount() == request ? wanted : simulated.getCount();
            debits.add(new Debit(source, slot, stack.copy(), count, simulated.getCount()));
            if (count == needed) {
                remaining.remove(material);
            } else {
                remaining.put(material, needed - count);
            }
        }
    }

    static int blockItemCount(BlockState state) {
        // DOUBLE_BLOCK_HALF marks the free upper half. SLAB_TYPE marks double slabs.
        if (state.getOptionalValue(BlockStateProperties.DOUBLE_BLOCK_HALF).orElse(null) == DoubleBlockHalf.UPPER) {
            return 0;
        }
        if (state.getOptionalValue(BlockStateProperties.SLAB_TYPE).orElse(null) == SlabType.DOUBLE) {
            return 2;
        }
        // CANDLES, PICKLES, LAYERS, EGGS and FLOWER_AMOUNT each count placements in one cell.
        for (IntegerProperty property : STACKED_BLOCK_COUNTS) {
            if (state.hasProperty(property)) {
                return state.getValue(property);
            }
        }
        // Vines, glow lichen and sculk veins use one item per attached face.
        if (state.getBlock() instanceof MultifaceBlock || state.getBlock() instanceof VineBlock) {
            return Math.max(1, MultifaceBlock.availableFaces(state).size());
        }
        return 1;
    }

    @Nullable
    Component shortage() {
        return shortage;
    }

    boolean commit(ServerPlayer player) {
        if (debits.stream().anyMatch(debit -> !debit.stillAvailable())) {
            return false;
        }
        List<Withdrawal> withdrawn = new ArrayList<>();
        for (Debit debit : debits) {
            int remaining = debit.count();
            while (remaining > 0) {
                int batch = Math.min(debit.batchSize(), remaining);
                ItemStack extracted = debit.source().handler().extractItem(debit.slot(), batch, false);
                if (!extracted.isEmpty()) {
                    withdrawn.add(new Withdrawal(debit, extracted));
                }
                if (extracted.getCount() != batch || !ItemStack.isSameItemSameComponents(debit.before(), extracted)) {
                    // An extraction callback can change another slot or a shared inventory during commit.
                    withdrawn.reversed().forEach(withdrawal -> withdrawal.restore(player));
                    return false;
                }
                remaining -= batch;
            }
        }
        return true;
    }

    private record Material(Item item, @Nullable BakedPart part) {}

    private record Source(IItemHandler handler, BooleanSupplier available) {}

    private record Debit(Source source, int slot, ItemStack before, int count, int batchSize) {
        boolean stillAvailable() {
            if (!source.available().getAsBoolean() || slot >= source.handler().getSlots()
                    || !ItemStack.matches(before, source.handler().getStackInSlot(slot))) {
                return false;
            }
            ItemStack simulated = source.handler().extractItem(slot, batchSize, true);
            return simulated.getCount() == batchSize && ItemStack.isSameItemSameComponents(before, simulated);
        }
    }

    private record Withdrawal(Debit debit, ItemStack stack) {
        void restore(ServerPlayer player) {
            Source source = debit.source();
            ItemStack leftover = source.available().getAsBoolean() && debit.slot() < source.handler().getSlots()
                    ? source.handler().insertItem(debit.slot(), stack, false) : stack;
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
        }
    }
}
