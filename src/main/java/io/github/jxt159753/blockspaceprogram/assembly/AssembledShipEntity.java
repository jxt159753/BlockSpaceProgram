package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;

/** The launched ship retains its graph and stages across saves. */
public final class AssembledShipEntity extends Entity {
    public static final String SAVED_OWNER_TAG = "ShipOwner";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int NO_SAVED_STAGE = -2;
    private static final String DOCKING = BlockSpaceProgram.MOD_ID + ":docking";
    private static final String SEATS = BlockSpaceProgram.MOD_ID + ":seats";
    private static final String LAST_SEAT = BlockSpaceProgram.MOD_ID + ":last_seat";
    private static final String DAMAGE = "DamagedParts";
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
    private static final EntityDataAccessor<Long> ELECTRIC = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<CompoundTag> DAMAGED_PARTS = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Integer> ACTIVE_STAGE = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ATTITUDE_MODE = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> THROTTLE_MODE = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<CompoundTag> FLIGHT_EFFECTS = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Boolean> RCS_ENABLED = SynchedEntityData.defineId(
            AssembledShipEntity.class, EntityDataSerializers.BOOLEAN);

    private final ShipFlightControls flightControls = new ShipFlightControls();
    @Nullable
    private UUID owner;
    @Nullable
    private SableShipFlight flight;
    @Nullable
    private UUID bodyId;
    private ShipPropellantData savedPropellant = ShipPropellantData.EMPTY;
    private ShipDockingState docking = ShipDockingState.EMPTY;
    private CompoundTag seats = new CompoundTag();
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
        builder.define(ELECTRIC, 0L);
        builder.define(DAMAGED_PARTS, new CompoundTag());
        builder.define(ACTIVE_STAGE, -1);
        builder.define(ATTITUDE_MODE, ShipFlightControl.AttitudeMode.MANUAL.ordinal());
        builder.define(THROTTLE_MODE, ShipFlightControl.ThrottleMode.MANUAL.ordinal());
        builder.define(FLIGHT_EFFECTS, new CompoundTag());
        builder.define(RCS_ENABLED, false);
    }

    @Nullable
    public ShipDesign design() {
        var blueprint = entityData.get(BLUEPRINT);
        var benchmark = blueprint.get(BlockSpaceProgram.BENCHMARK_LAYOUT);
        return benchmark == null ? design(blueprint) : benchmark;
    }

    public boolean isBenchmark() {
        return entityData.get(BLUEPRINT).has(BlockSpaceProgram.BENCHMARK_LAYOUT);
    }

    @Nullable
    static ShipDesign design(ItemStack stack) {
        ShipDesign layout = stack.get(BlockSpaceProgram.FLIGHT_LAYOUT);
        return layout == null ? stack.get(BlockSpaceProgram.SHIP_DESIGN) : layout;
    }

    void setBlueprint(ItemStack stack) {
        entityData.set(BLUEPRINT, stack.copyWithCount(1));
    }

    void setFlightDesign(ShipDesign layout) {
        var stack = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        stack.set(BlockSpaceProgram.FLIGHT_LAYOUT, layout);
        if (!seats.isEmpty()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> data.put(SEATS, seats.copy()));
        }
        setBlueprint(stack);
    }

    @Nullable
    public static AssembledShipEntity spawnBenchmark(ServerLevel level, ShipDesign design, Vec3 origin) {
        var blueprint = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        blueprint.set(BlockSpaceProgram.BENCHMARK_LAYOUT, design);
        return spawn(level, blueprint, origin, OctahedralGroup.IDENTITY, null);
    }

    @Nullable
    static AssembledShipEntity spawn(ServerLevel level, ItemStack blueprint, Vec3 origin, OctahedralGroup rotation,
            @Nullable UUID owner) {
        var ship = new AssembledShipEntity(BlockSpaceProgram.ASSEMBLED_SHIP.get(), level);
        ship.owner = owner;
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
        }
        NeoForge.EVENT_BUS.addListener(PlanetFlightProperties::register);
        PlanetEntityGravity.register();
    }

    private boolean startFlight(ServerLevel level) {
        ShipDesign design = design();
        if (design == null) {
            return false;
        }
        try {
            bindFlight(bodyId == null ? SableShipFlight.launch(this, level, design)
                    : SableShipFlight.restore(this, level, design, bodyId, savedPropellant, activeStage()));
            return true;
        } catch (RuntimeException error) {
            LOGGER.warn("Could not initialize ship flight for {}", getUUID(), error);
            return false;
        }
    }

    @Nullable
    public SableShipFlight activeFlight() {
        return flight != null && flight.isActive() ? flight : null;
    }

    /** Center in dimension coordinates, including for ships whose entity sits in a Sable plot. */
    public Vec3 dimensionCenter() {
        var active = activeFlight();
        return active == null ? position() : active.motion().center();
    }

    @Nullable
    public ShipFlightControl flightControl() {
        var active = activeFlight();
        return active != null && !active.isDebris() ? active : null;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    void inheritOwner(AssembledShipEntity primary) {
        owner = primary.owner;
    }

    ShipDockingState.Identity dockingIdentity() {
        return new ShipDockingState.Identity(getUUID(), Optional.ofNullable(owner), Optional.ofNullable(getCustomName()));
    }

    void restoreDockingIdentity(ShipDockingState.Identity identity) {
        setUUID(identity.id());
        owner = identity.owner().orElse(null);
        setCustomName(identity.name().orElse(null));
    }

    ShipDockingState dockingState() {
        return docking;
    }

    void setDockingState(ShipDockingState docking) {
        this.docking = docking;
        if (docking.equals(ShipDockingState.EMPTY)) {
            getPersistentData().remove(DOCKING);
        } else {
            var ops = registryAccess().createSerializationContext(NbtOps.INSTANCE);
            getPersistentData().put(DOCKING, ShipDockingState.Serialization.CODEC.encodeStart(ops, docking).getOrThrow());
        }
    }

    ShipFlightControls flightControls() {
        return flightControls;
    }

    static List<AssembledShipEntity> ownedFlights(ServerLevel level, UUID owner) {
        return ModList.get().isLoaded("sable") ? SableShipFlight.ownedShips(level, owner) : List.of();
    }

    @Nullable
    public static AssembledShipEntity findOwnedFlight(MinecraftServer server, UUID id, UUID owner) {
        for (var level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof AssembledShipEntity ship && owner.equals(ship.owner())
                    && ship.flightControl() != null) {
                return ship;
            }
        }
        return null;
    }

    public CompoundTag orbitSnapshot() {
        var tag = new CompoundTag();
        // Players keep their own data and cross dimensions as the same server-side entity.
        if (!saveAsPassenger(tag) || snapshotSize(tag) != getSelfAndPassengers()
                .filter(member -> !(member instanceof Player)).count()) {
            throw new IllegalArgumentException("The ship and all of its passengers must be serializable");
        }
        return tag;
    }

    @Nullable
    public static AssembledShipEntity restoreFromOrbit(ServerLevel level, UUID id, CompoundTag tag,
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
            ship.bindFlight(SableShipFlight.rebuild(ship, level, design, ship.savedPropellant, motion));
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
    }

    private void bindFlight(SableShipFlight flight) {
        this.flight = flight;
        triedFlight = true;
        bodyId = flight.bodyId();
        savedPropellant = ShipPropellantData.EMPTY;
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

    public double electric() {
        return Double.longBitsToDouble(entityData.get(ELECTRIC));
    }

    public ShipFlightControl.Propellant propellant() {
        return flight == null ? new ShipFlightControl.Propellant(fuel(), electric()) : flight.propellant();
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
        return damagedParts().cardinality();
    }

    BitSet damagedParts() {
        return BitSet.valueOf(entityData.get(DAMAGED_PARTS).getLongArray(DAMAGE));
    }

    void setDamagedParts(BitSet damage) {
        var tag = new CompoundTag();
        if (!damage.isEmpty()) {
            tag.putLongArray(DAMAGE, damage.toLongArray());
        }
        entityData.set(DAMAGED_PARTS, tag);
    }

    static BitSet readDamagedParts(CompoundTag tag) {
        Tag damage = tag.get(DAMAGE);
        if (damage instanceof LongArrayTag array && array.size() <= ShipDesign.MAX_NODE_WORDS) {
            var parts = BitSet.valueOf(array.getAsLongArray());
            if (parts.length() <= ShipDesign.MAX_MERGED_NODES) {
                return parts;
            }
        }
        if (damage != null) {
            LOGGER.warn("Ignoring invalid {} in saved ship data (tag type {})", DAMAGE, damage.getType().getName());
        }
        return new BitSet();
    }

    public byte[] parachuteStates() {
        return entityData.get(FLIGHT_EFFECTS).getByteArray("states");
    }

    void setParachuteStates(byte[] states) {
        if (!Arrays.equals(parachuteStates(), states)) {
            var tag = entityData.get(FLIGHT_EFFECTS).copy();
            tag.putByteArray("states", states);
            entityData.set(FLIGHT_EFFECTS, tag);
        }
    }

    public float reentryHeatRatio() {
        return entityData.get(FLIGHT_EFFECTS).getFloat("heat");
    }

    public Vector3f reentryWindward() {
        var tag = entityData.get(FLIGHT_EFFECTS);
        return new Vector3f(tag.getFloat("wind_x"), tag.getFloat("wind_y"), tag.getFloat("wind_z"));
    }

    void setReentryStatus(float ratio, Vector3fc windward) {
        var current = entityData.get(FLIGHT_EFFECTS);
        if (current.getFloat("heat") != ratio || current.getFloat("wind_x") != windward.x()
                || current.getFloat("wind_y") != windward.y() || current.getFloat("wind_z") != windward.z()) {
            var tag = current.copy();
            tag.putFloat("heat", ratio);
            tag.putFloat("wind_x", windward.x());
            tag.putFloat("wind_y", windward.y());
            tag.putFloat("wind_z", windward.z());
            entityData.set(FLIGHT_EFFECTS, tag);
        }
    }

    public ShipFlightControl.AttitudeMode attitudeMode() {
        return ShipFlightControl.AttitudeMode.values()[entityData.get(ATTITUDE_MODE)];
    }

    public ShipFlightControl.ThrottleMode throttleMode() {
        return ShipFlightControl.ThrottleMode.values()[entityData.get(THROTTLE_MODE)];
    }

    public boolean rcsEnabled() {
        return entityData.get(RCS_ENABLED);
    }

    void setFlightStatus(float fuel, double electric, int stage, boolean rcsEnabled) {
        entityData.set(THROTTLE, (int) Math.round(100 * flightControls.throttle()));
        entityData.set(FUEL, fuel);
        entityData.set(ELECTRIC, Double.doubleToLongBits(electric));
        entityData.set(ACTIVE_STAGE, stage);
        entityData.set(ATTITUDE_MODE, flightControls.attitudeMode().ordinal());
        entityData.set(THROTTLE_MODE, flightControls.throttleMode().ordinal());
        entityData.set(RCS_ENABLED, rcsEnabled);
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
        // Real hull blocks handle their own interactions. Keep the entity hit box at the command pod.
        AABB bounds = design.hasHull() ? design.nodes().stream().filter(ShipDesign.Node::isCommandPod)
                .findFirst().orElse(design.nodes().getFirst()).bounds() : design.bounds();
        return (isFlyingShip() ? bounds : PartPose.rotateBounds(launchRotation(), bounds)).move(position());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BLUEPRINT.equals(key)) {
            seats = entityData.get(BLUEPRINT).getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                    .copyTag().getCompound(SEATS);
            reapplyPosition();
        } else if (LAUNCH_ROTATION.equals(key)) {
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
        if (isBenchmark()) {
            return InteractionResult.PASS;
        }
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
        if (design != null && design.hasHull() && !isDebris() && stack.isEmpty()
                && player.isSecondaryUseActive() && !player.isSpectator()) {
            if (player instanceof ServerPlayer serverPlayer) {
                AssemblyMenu.openFlight(serverPlayer, this);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (!isFlyingShip() || player.isSpectator() || player.isSecondaryUseActive() || availableSeat(player) < 0) {
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
        return !isBenchmark() && isFlyingShip() && passenger instanceof Player player && availableSeat(player) >= 0;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        int pod = assignedSeat(passenger);
        if (pod > 0 && isPod(pod)) {
            return ShipFlightState.seat(design(), pod);
        }
        return new Vec3(entityData.get(SEAT));
    }

    int assignedSeat(Entity passenger) {
        return seats.getInt(passenger.getStringUUID());
    }

    int availableSeat(Player player) {
        ShipDesign design = design();
        if (design == null) {
            return -1;
        }
        var remembered = player.getPersistentData().getCompound(LAST_SEAT);
        int preferred = remembered.getInt("pod");
        if (remembered.hasUUID("ship") && remembered.getUUID("ship").equals(getUUID())
                && isPod(preferred) && seatFree(preferred)) {
            return preferred;
        }
        for (int i = 0; i < design.nodes().size(); i++) {
            if (design.nodes().get(i).isCommandPod() && seatFree(i)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isPod(int part) {
        ShipDesign design = design();
        return design != null && part >= 0 && part < design.nodes().size() && design.nodes().get(part).isCommandPod();
    }

    private boolean seatFree(int part) {
        return getPassengers().stream().noneMatch(passenger -> assignedSeat(passenger) == part);
    }

    private void syncSeats() {
        var stack = entityData.get(BLUEPRINT).copy();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> {
            if (seats.isEmpty()) {
                data.remove(SEATS);
            } else {
                data.put(SEATS, seats.copy());
            }
        });
        setBlueprint(stack);
    }

    void assignSeat(Player player, int pod) {
        seats.putInt(player.getStringUUID(), pod);
        syncSeats();
        var remembered = new CompoundTag();
        remembered.putUUID("ship", getUUID());
        remembered.putInt("pod", pod);
        player.getPersistentData().put(LAST_SEAT, remembered);
    }

    void clearSeat(Entity passenger) {
        if (seats.contains(passenger.getStringUUID())) {
            seats.remove(passenger.getStringUUID());
            syncSeats();
        }
    }

    @Override
    protected void addPassenger(Entity passenger) {
        if (!level().isClientSide && passenger instanceof Player player && !seats.contains(player.getStringUUID())) {
            int seat = availableSeat(player);
            if (seat >= 0) {
                assignSeat(player, seat);
            }
        }
        super.addPassenger(passenger);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return getPassengerRidingPosition(passenger);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        boolean wasPilot = getFirstPassenger() == passenger;
        super.removePassenger(passenger);
        if (!level().isClientSide) {
            clearSeat(passenger);
        }
        if (flight != null) {
            if (wasPilot) {
                flight.clearInput();
            }
            if (passenger instanceof ServerPlayer player && flight.isActive()) {
                flight.inheritDismountVelocity(player);
            }
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
    public boolean shouldBeSaved() {
        return !isBenchmark() && super.shouldBeSaved();
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (flight != null && reason.shouldDestroy()) {
            flight.discard();
            if (isBenchmark()) {
                flight.removeBodyNow();
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(LAUNCH_ROTATION, tag.getByte("LaunchRotation"));
        setBlueprint(ItemStack.parseOptional(registryAccess(), tag.getCompound("Blueprint")));
        owner = tag.hasUUID(SAVED_OWNER_TAG) ? tag.getUUID(SAVED_OWNER_TAG) : null;
        Tag savedDocking = getPersistentData().get(DOCKING);
        if (savedDocking != null) {
            var ops = registryAccess().createSerializationContext(NbtOps.INSTANCE);
            var saved = ShipDockingState.Serialization.CODEC.parse(ops, savedDocking);
            docking = saved.result().orElse(ShipDockingState.EMPTY);
            saved.error().ifPresent(error -> {
                LOGGER.warn("Ignoring invalid docking state for ship {}: {}", getUUID(), error.message());
                getPersistentData().remove(DOCKING);
            });
        }
        if (tag.hasUUID("FlightBody")) {
            bodyId = tag.getUUID("FlightBody");
            savedPropellant = ShipPropellantData.read(tag);
            entityData.set(FLYING, true);
            entityData.set(ACTIVE_STAGE, tag.contains("FlightStage") ? tag.getInt("FlightStage") : NO_SAVED_STAGE);
            setDamagedParts(readDamagedParts(tag));
            debrisSince = tag.contains("DebrisSince") ? tag.getLong("DebrisSince") : -1;
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Blueprint", entityData.get(BLUEPRINT).saveOptional(registryAccess()));
        tag.putByte("LaunchRotation", entityData.get(LAUNCH_ROTATION));
        if (owner != null) {
            tag.putUUID(SAVED_OWNER_TAG, owner);
        }
        if (bodyId != null) {
            tag.putUUID("FlightBody", bodyId);
            (flight == null ? savedPropellant : flight.savePropellant()).write(tag);
            tag.putLongArray(DAMAGE, entityData.get(DAMAGED_PARTS).getLongArray(DAMAGE));
            if (activeStage() >= -1) {
                tag.putInt("FlightStage", activeStage());
            }
            if (debrisSince >= 0) {
                tag.putLong("DebrisSince", debrisSince);
            }
        }
    }
}
