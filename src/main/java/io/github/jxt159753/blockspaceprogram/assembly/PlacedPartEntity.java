package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private static final EntityDataAccessor<Byte> ROTATION =
            SynchedEntityData.defineId(PlacedPartEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<CompoundTag> GROUP =
            SynchedEntityData.defineId(PlacedPartEntity.class, EntityDataSerializers.COMPOUND_TAG);

    public PlacedPartEntity(EntityType<? extends PlacedPartEntity> type, Level level) {
        super(type, level);
        blocksBuilding = true;
    }

    public static PlacedPartEntity create(Level level, ItemStack stack, Vec3 origin, OctahedralGroup rotation) {
        var entity = new PlacedPartEntity(BlockSpaceProgram.PLACED_PART.get(), level);
        entity.setPartStack(stack);
        entity.entityData.set(ROTATION, (byte) rotation.ordinal());
        entity.setPos(origin);
        return entity;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(PART_STACK, ItemStack.EMPTY);
        builder.define(ROTATION, (byte) OctahedralGroup.IDENTITY.ordinal());
        builder.define(GROUP, new CompoundTag());
    }

    @Nullable
    public BakedPart part() {
        return entityData.get(PART_STACK).get(BlockSpaceProgram.PART_DATA);
    }

    void setPartStack(ItemStack stack) {
        entityData.set(PART_STACK, stack.is(BlockSpaceProgram.BAKED_PART.get()) ? stack.copyWithCount(1) : ItemStack.EMPTY);
    }

    public OctahedralGroup rotation() {
        return PartPose.rotationById(entityData.get(ROTATION));
    }

    public boolean isCommandPod() {
        var part = part();
        return part != null && part.types().contains(PartType.COMMAND_POD);
    }

    public void setGroupInfo(int size, int[] markers) {
        var tag = new CompoundTag();
        tag.putInt("Size", size);
        tag.putIntArray("Markers", markers);
        entityData.set(GROUP, tag);
    }

    public int groupSize() {
        return Math.max(1, entityData.get(GROUP).getInt("Size"));
    }

    public boolean isMarkerOccupied(int marker) {
        for (int used : entityData.get(GROUP).getIntArray("Markers")) {
            if (used == marker) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected AABB makeBoundingBox() {
        BakedPart part = part();
        return part == null ? super.makeBoundingBox() : PartPose.rotateBounds(rotation(), part.localBounds()).move(position());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PART_STACK.equals(key) || ROTATION.equals(key)) {
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
        if (!WorldAssembly.canUse(player, this)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty() && hand == InteractionHand.MAIN_HAND) {
            stack = player.getOffhandItem();
        }
        if (stack.is(BlockSpaceProgram.BAKED_PART.get())) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (stack.is(BlockSpaceProgram.SHIP_ASSEMBLER.get())) {
                String error = WorldAssembly.assemble(serverPlayer, this);
                if (error != null) {
                    player.displayClientMessage(Component.translatable("assembly.blockspaceprogram." + error), true);
                    return InteractionResult.FAIL;
                }
                return InteractionResult.CONSUME;
            }
            if (!player.isSecondaryUseActive() && isCommandPod()) {
                WorldAssembly.open(serverPlayer, this);
            } else {
                player.getInventory().placeItemBackInInventory(getPickResult());
                discard();
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel server) {
            WorldAssembly.onPartAdded(server, this);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        boolean removed = isRemoved();
        super.remove(reason);
        if (!removed && (reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION)
                && level() instanceof ServerLevel server) {
            WorldAssembly.onPartRemoved(server, this);
        }
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
        entityData.set(ROTATION, tag.getByte("Rotation"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Part", entityData.get(PART_STACK).saveOptional(registryAccess()));
        tag.putByte("Rotation", entityData.get(ROTATION));
    }
}
