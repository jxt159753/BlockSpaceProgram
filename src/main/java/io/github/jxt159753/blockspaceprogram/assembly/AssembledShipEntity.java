package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import com.mojang.math.OctahedralGroup;
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
    private static final int NO_SAVED_STAGE = -2;
    private static final EntityDataAccessor<ItemStack> BLUEPRINT = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Byte> LAUNCH_ROTATION = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Vector3f> SEAT = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Integer> THROTTLE = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> FUEL = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Long> DAMAGED_PARTS = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> ACTIVE_STAGE = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.INT);

    @Nullable
    private SableShipFlight flight;
    @Nullable
    private UUID bodyId;
    private ListTag savedFuel = new ListTag();
    private long debrisSince = -1;
    private boolean triedFlight;

    public AssembledShipEntity(EntityType<? extends AssembledShipEntity> type, Level level) {
        super(type, level);
        blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(BLUEPRINT, ItemStack.EMPTY);
        builder.define(LAUNCH_ROTATION, (byte) OctahedralGroup.IDENTITY.ordinal());
        builder.define(FLYING, false);
        builder.define(SEAT, new Vector3f());
        builder.define(THROTTLE, 0);
        builder.define(FUEL, 0.0F);
        builder.define(DAMAGED_PARTS, 0L);
        builder.define(ACTIVE_STAGE, -1);
    }

    @Nullable
    public ShipDesign design() {
        var stack = entityData.get(BLUEPRINT);
        ShipDesign layout = stack.get(BlockSpaceProgram.FLIGHT_LAYOUT);
        return layout == null ? stack.get(BlockSpaceProgram.SHIP_DESIGN) : layout;
    }

    void setBlueprint(ItemStack stack) {
        entityData.set(BLUEPRINT, stack.copyWithCount(1));
    }

    void setFlightDesign(ShipDesign layout) {
        var stack = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        stack.set(BlockSpaceProgram.FLIGHT_LAYOUT, layout);
        setBlueprint(stack);
    }

    @Nullable
    static AssembledShipEntity spawn(ServerLevel level, ItemStack blueprint, Vec3 origin, OctahedralGroup rotation) {
        var ship = new AssembledShipEntity(BlockSpaceProgram.ASSEMBLED_SHIP.get(), level);
        ship.setBlueprint(blueprint);
        ship.entityData.set(LAUNCH_ROTATION, (byte) rotation.ordinal());
        ship.setPos(origin);
        ship.triedFlight = true;
        if ((ModList.get().isLoaded("sable") && !ship.startFlight(level)) || !level.addFreshEntity(ship)) {
            ship.discard();
            return null;
        }
        return ship;
    }

    public OctahedralGroup launchRotation() {
        return PartPose.rotationById(entityData.get(LAUNCH_ROTATION));
    }

    public static void registerFlight() {
        if (ModList.get().isLoaded("sable")) {
            SableShipFlight.register();
            NeoForge.EVENT_BUS.addListener(ShipOrbitCommands::register);
        }
        NeoForge.EVENT_BUS.addListener(PlanetFlightProperties::register);
    }

    private boolean startFlight(ServerLevel level) {
        ShipDesign design = design();
        if (design == null) {
            return false;
        }
        try {
            bindFlight(bodyId == null ? SableShipFlight.launch(this, level, design)
                    : SableShipFlight.restore(this, level, design, bodyId, savedFuel, activeStage()));
            return true;
        } catch (RuntimeException error) {
            LOGGER.warn("Could not initialize ship flight for {}", getUUID(), error);
            return false;
        }
    }

    @Nullable
    SableShipFlight activeFlight() {
        return flight != null && flight.isActive() ? flight : null;
    }

    CompoundTag orbitSnapshot() {
        var tag = new CompoundTag();
        if (!saveAsPassenger(tag) || snapshotSize(tag) != getSelfAndPassengers().count()) {
            throw new IllegalArgumentException("The ship and all of its passengers must be serializable");
        }
        return tag;
    }

    @Nullable
    static AssembledShipEntity restoreFromOrbit(ServerLevel level, UUID id, CompoundTag tag,
            SableShipFlight.Motion motion, boolean reentry) {
        var entity = EntityType.loadEntityRecursive(tag, level, loaded -> loaded);
        if (!(entity instanceof AssembledShipEntity ship) || !id.equals(ship.getUUID()) || ship.bodyId == null) {
            return null;
        }
        ShipDesign design = ship.design();
        var members = ship.getSelfAndPassengers().toList();
        if (design == null || members.size() != snapshotSize(tag)
                || members.stream().anyMatch(member -> level.getEntity(member.getUUID()) != null)) {
            return null;
        }

        boolean restored = false;
        try {
            ship.bindFlight(SableShipFlight.rebuild(ship, level, design, ship.savedFuel, motion));
            ship.flight.setMotion(motion);
            AABB bounds = ship.flight.worldBounds();
            if (!level.getWorldBorder().isWithinBounds(bounds) || (reentry && bounds.minY < level.getMaxBuildHeight())) {
                return null;
            }
            ship.setPos(ship.flight.plotPosition());
            for (Entity member : members) {
                if (member.isPassenger()) {
                    member.getVehicle().positionRider(member);
                }
                if (!level.addFreshEntity(member)) {
                    return null;
                }
            }
            restored = true;
            return ship;
        } finally {
            if (!restored) {
                members.reversed().forEach(Entity::discard);
                if (ship.flight != null) {
                    ship.flight.removeBodyNow();
                }
            }
        }
    }

    private static int snapshotSize(CompoundTag tag) {
        int size = 1;
        var passengers = tag.getList("Passengers", Tag.TAG_COMPOUND);
        for (int index = 0; index < passengers.size(); index++) {
            size += snapshotSize(passengers.getCompound(index));
        }
        return size;
    }

    void useFlightFragment(ShipDesign design, SableShipFlight flight) {
        setFlightDesign(design);
        setPos(flight.plotPosition());
        bindFlight(flight);
        if (getFirstPassenger() instanceof Player pilot) {
            positionRider(pilot);
        }
    }

    private void bindFlight(SableShipFlight flight) {
        this.flight = flight;
        triedFlight = true;
        bodyId = flight.bodyId();
        savedFuel = new ListTag();
        if (flight.isDebris() && debrisSince < 0) {
            debrisSince = level().getGameTime();
        }
        entityData.set(FLYING, true);
        entityData.set(SEAT, flight.seat().toVector3f());
        setDamagedParts(flight.damagedParts());
        flight.syncStatus();
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

    public int activeStage() {
        return entityData.get(ACTIVE_STAGE);
    }

    public boolean isDebris() {
        ShipDesign design = design();
        return design != null && design.nodes().stream().noneMatch(ShipDesign.Node::isCommandPod);
    }

    long debrisSince() {
        return debrisSince;
    }

    public int damagedPartCount() {
        return Long.bitCount(damagedParts());
    }

    long damagedParts() {
        return entityData.get(DAMAGED_PARTS);
    }

    void setDamagedParts(long damage) {
        entityData.set(DAMAGED_PARTS, damage);
    }

    void setFlightStatus(int throttle, float fuel, int stage) {
        entityData.set(THROTTLE, throttle);
        entityData.set(FUEL, fuel);
        entityData.set(ACTIVE_STAGE, stage);
    }

    void acceptFlightInput(ServerPlayer pilot, int keys) {
        if (flight != null && getFirstPassenger() == pilot && pilot.isAlive() && !pilot.isSpectator()) {
            flight.updateInput(keys, level().getGameTime());
        }
    }

    @Override
    protected AABB makeBoundingBox() {
        ShipDesign design = design();
        if (design == null) {
            return super.makeBoundingBox();
        }
        AABB bounds = design.bounds();
        return (isFlyingShip() ? bounds : PartPose.rotateBounds(launchRotation(), bounds)).move(position());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BLUEPRINT.equals(key) || LAUNCH_ROTATION.equals(key)) {
            reapplyPosition();
        } else if (FLYING.equals(key)) {
            blocksBuilding = !isFlyingShip();
            reapplyPosition();
        }
    }

    @Override
    public boolean canBeCollidedWith() {
        return isAlive() && !isFlyingShip() && design() != null;
    }

    @Override
    public boolean isPickable() {
        return isAlive() && design() != null;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty() && hand == InteractionHand.MAIN_HAND) {
            stack = player.getOffhandItem();
        }
        ShipDesign design = design();
        if (stack.is(BlockSpaceProgram.SHIP_BLUEPRINT.get()) && !stack.has(BlockSpaceProgram.SHIP_DESIGN)
                && player.getAbilities().mayBuild && !player.isSpectator() && design != null && design.isValid()) {
            if (!level().isClientSide) {
                stack.set(BlockSpaceProgram.SHIP_DESIGN, design);
                player.getInventory().setChanged();
                player.displayClientMessage(Component.translatable("assembly.blockspaceprogram.saved"), true);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (!isFlyingShip() || isDebris() || player.isSpectator() || player.isSecondaryUseActive() || getFirstPassenger() != null) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (flight != null && player.startRiding(this)) {
            player.displayClientMessage(Component.translatable("flight.blockspaceprogram.controls",
                    Component.keybind("key.blockspaceprogram.throttle_up"), Component.keybind("key.blockspaceprogram.throttle_down"),
                    Component.keybind("key.blockspaceprogram.cutoff"), Component.keybind("key.jump")), true);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return isFlyingShip() && !isDebris() && passenger instanceof Player && getPassengers().isEmpty();
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
                flight.tickControls();
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
        entityData.set(LAUNCH_ROTATION, tag.getByte("LaunchRotation"));
        setBlueprint(ItemStack.parseOptional(registryAccess(), tag.getCompound("Blueprint")));
        if (tag.hasUUID("FlightBody")) {
            bodyId = tag.getUUID("FlightBody");
            savedFuel = tag.getList("Fuel", Tag.TAG_DOUBLE);
            entityData.set(FLYING, true);
            entityData.set(ACTIVE_STAGE, tag.contains("FlightStage") ? tag.getInt("FlightStage") : NO_SAVED_STAGE);
            setDamagedParts(tag.getLong("DamagedParts"));
            debrisSince = tag.contains("DebrisSince") ? tag.getLong("DebrisSince") : -1;
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Blueprint", entityData.get(BLUEPRINT).saveOptional(registryAccess()));
        tag.putByte("LaunchRotation", entityData.get(LAUNCH_ROTATION));
        if (bodyId != null) {
            tag.putUUID("FlightBody", bodyId);
            tag.put("Fuel", flight == null ? savedFuel : flight.saveFuel());
            tag.putLong("DamagedParts", damagedParts());
            if (activeStage() >= -1) {
                tag.putInt("FlightStage", activeStage());
            }
            if (debrisSince >= 0) {
                tag.putLong("DebrisSince", debrisSince);
            }
        }
    }
}
