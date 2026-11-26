package io.github.jxt159753.blockspaceprogram.part;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class PlacedPartEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> PART_STACK =
            SynchedEntityData.defineId(PlacedPartEntity.class, EntityDataSerializers.ITEM_STACK);

    public PlacedPartEntity(EntityType<? extends PlacedPartEntity> type, Level level) {
        super(type, level);
        blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(PART_STACK, ItemStack.EMPTY);
    }

    @Nullable
    public BakedPart part() {
        return entityData.get(PART_STACK).get(BlockSpaceProgram.PART_DATA);
    }

    void setPartStack(ItemStack stack) {
        entityData.set(PART_STACK, stack.is(BlockSpaceProgram.BAKED_PART.get()) ? stack.copyWithCount(1) : ItemStack.EMPTY);
    }

    @Override
    protected AABB makeBoundingBox() {
        BakedPart part = part();
        return part == null ? super.makeBoundingBox() : part.localBounds().move(position());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PART_STACK.equals(key)) {
            reapplyPosition();
        }
    }

    @Override
    public Vec3 getLightProbePosition(float partialTick) {
        return getBoundingBox().getCenter();
    }

    @Override
    public boolean isPickable() {
        return isAlive() && part() != null;
    }

    @Override
    public boolean canBeCollidedWith() {
        return isPickable();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!isPickable() || !player.getAbilities().mayBuild || !level().mayInteract(player, blockPosition())) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide) {
            player.getInventory().placeItemBackInInventory(getPickResult());
            discard();
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public ItemStack getPickResult() {
        return entityData.get(PART_STACK).copy();
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && part() == null) {
            discard();
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setPartStack(ItemStack.parseOptional(registryAccess(), tag.getCompound("Part")));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Part", entityData.get(PART_STACK).saveOptional(registryAccess()));
    }
}
