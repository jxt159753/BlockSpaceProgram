package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;

/** The launched ship retains its graph and stages across saves. */
public final class AssembledShipEntity extends Entity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EntityDataAccessor<ItemStack> BLUEPRINT = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Vector3f> SEAT = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Integer> THROTTLE = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> FUEL = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.FLOAT);

    @Nullable
    private SableShipFlight flight;
    @Nullable
    private UUID bodyId;
    private ListTag savedFuel = new ListTag();
    private boolean triedFlight;

    public AssembledShipEntity(EntityType<? extends AssembledShipEntity> type, Level level) {
        super(type, level);
        blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(BLUEPRINT, ItemStack.EMPTY);
        builder.define(FLYING, false);
        builder.define(SEAT, new Vector3f());
        builder.define(THROTTLE, 0);
        builder.define(FUEL, 0.0F);
    }

    @Nullable
    public ShipDesign design() {
        return entityData.get(BLUEPRINT).get(BlockSpaceProgram.SHIP_DESIGN);
    }

    void setBlueprint(ItemStack stack) {
        entityData.set(BLUEPRINT, stack.copyWithCount(1));
    }

    public static void registerFlight() {
        if (ModList.get().isLoaded("sable")) {
            SableShipFlight.register();
        }
        NeoForge.EVENT_BUS.addListener(PlanetFlightProperties::register);
    }

    boolean prepareLaunch(ServerLevel level) {
        triedFlight = true;
        return !ModList.get().isLoaded("sable") || startFlight(level);
    }

    private boolean startFlight(ServerLevel level) {
        ShipDesign design = design();
        if (design == null) {
            return false;
        }
        try {
            flight = bodyId == null ? SableShipFlight.launch(this, level, design)
                    : SableShipFlight.restore(this, level, design, bodyId, savedFuel);
            bodyId = flight.bodyId();
            savedFuel = new ListTag();
            entityData.set(FLYING, true);
            entityData.set(SEAT, flight.seat().toVector3f());
            flight.tickControls(this);
            return true;
        } catch (RuntimeException error) {
            LOGGER.warn("Could not initialize ship flight for {}", getUUID(), error);
            return false;
        }
    }

    public boolean isFlyingShip() {
        return entityData.get(FLYING);
    }

    public int throttle() {
        return entityData.get(THROTTLE);
    }

    public float fuel() {
        return entityData.get(FUEL);
    }

    void setFlightStatus(int throttle, float fuel) {
        entityData.set(THROTTLE, throttle);
        entityData.set(FUEL, fuel);
    }

    void acceptFlightInput(ServerPlayer pilot, int keys) {
        if (flight != null && getFirstPassenger() == pilot && pilot.isAlive() && !pilot.isSpectator()) {
            flight.updateInput(keys, level().getGameTime());
        }
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
        } else if (FLYING.equals(key)) {
            blocksBuilding = !isFlyingShip();
        }
    }

    @Override
    public boolean canBeCollidedWith() {
        return isAlive() && !isFlyingShip() && design() != null;
    }

    @Override
    public boolean isPickable() {
        return isAlive() && isFlyingShip();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!isFlyingShip() || player.isSpectator() || player.isSecondaryUseActive() || getFirstPassenger() != null) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (flight != null && player.startRiding(this)) {
            player.displayClientMessage(Component.translatable("flight.blockspaceprogram.controls",
                    Component.keybind("key.blockspaceprogram.throttle_up"), Component.keybind("key.blockspaceprogram.throttle_down"),
                    Component.keybind("key.blockspaceprogram.cutoff")), true);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return isFlyingShip() && passenger instanceof Player && getPassengers().isEmpty();
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        return new Vec3(entityData.get(SEAT));
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return getPassengerRidingPosition(passenger);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (flight != null) {
            flight.clearInput();
        }
    }

    @Override
    public Vec3 getLightProbePosition(float partialTick) {
        return getBoundingBox().getCenter();
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel server) {
            if (design() == null) {
                discard();
                return;
            }
            if (!triedFlight && ModList.get().isLoaded("sable")) {
                triedFlight = true;
                if (startFlight(server)) {
                    flight.attach();
                } else if (bodyId != null) {
                    discard();
                }
            }
            if (flight != null) {
                flight.tickControls(this);
            }
        }
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (flight != null) {
            flight.attach();
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (flight != null) {
            flight.detach();
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (flight != null && reason.shouldDestroy()) {
            flight.discard();
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setBlueprint(ItemStack.parseOptional(registryAccess(), tag.getCompound("Blueprint")));
        if (tag.hasUUID("FlightBody")) {
            bodyId = tag.getUUID("FlightBody");
            savedFuel = tag.getList("Fuel", Tag.TAG_DOUBLE);
            entityData.set(FLYING, true);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Blueprint", entityData.get(BLUEPRINT).saveOptional(registryAccess()));
        if (bodyId != null) {
            tag.putUUID("FlightBody", bodyId);
            tag.put("Fuel", flight == null ? savedFuel : flight.saveFuel());
        }
    }
}
