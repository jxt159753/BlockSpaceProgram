package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** The launched ship retains its graph and stages across saves. */
public final class AssembledShipEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> BLUEPRINT = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.ITEM_STACK);

    public AssembledShipEntity(EntityType<? extends AssembledShipEntity> type, Level level) {
        super(type, level);
        blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(BLUEPRINT, ItemStack.EMPTY);
    }

    @Nullable
    public ShipDesign design() {
        return entityData.get(BLUEPRINT).get(BlockSpaceProgram.SHIP_DESIGN);
    }

    void setBlueprint(ItemStack stack) {
        entityData.set(BLUEPRINT, stack.copyWithCount(1));
    }

    @Override
    protected AABB makeBoundingBox() {
        ShipDesign design = design();
        return design == null ? super.makeBoundingBox() : design.bounds().move(position());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BLUEPRINT.equals(key)) {
            reapplyPosition();
        }
    }

    @Override
    public boolean canBeCollidedWith() {
        return isAlive() && design() != null;
    }

    @Override
    public Vec3 getLightProbePosition(float partialTick) {
        return getBoundingBox().getCenter();
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && design() == null) {
            discard();
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setBlueprint(ItemStack.parseOptional(registryAccess(), tag.getCompound("Blueprint")));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Blueprint", entityData.get(BLUEPRINT).saveOptional(registryAccess()));
    }
}
