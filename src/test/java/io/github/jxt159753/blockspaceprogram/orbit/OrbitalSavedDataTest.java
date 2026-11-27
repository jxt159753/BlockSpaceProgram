package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.IOUtilities;
import org.joml.Quaterniond;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OrbitalSavedDataTest {
    private static final HolderLookup.Provider REGISTRIES = HolderLookup.Provider.create(Stream.empty());
    @Rule
    public final TemporaryFolder worldData = new TemporaryFolder();

    @BeforeClass
    public static void detectGameVersion() {
        SharedConstants.tryDetectVersion();
    }

    @Test
    public void loadsClockAndFullShipAfterReopeningWorldData() {
        var storage = openStorage();
        var starSystem = StarSystem.generate(StarSystem.Spacing.NEAR, StarSystem.Shape.NATURAL, Long.MIN_VALUE);
        var data = new OrbitalSavedData(starSystem);
        storage.set(OrbitalSavedData.STORAGE_ID, data);
        UUID id = UUID.randomUUID();
        var orientation = new Quaterniond().rotateXYZ(0.2, -0.7, 0.4);
        var angularVelocity = new Vec3(0.11, -0.04, 0.23);
        var shipData = new CompoundTag();
        shipData.putDouble("remaining_fuel", 14.25);
        var savedShip = new OrbitingShip(id, new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0)), orientation, angularVelocity, shipData);
        data.put(savedShip);
        data.advanceTicks(REGISTRIES, 100_000);
        data.advanceTicks(REGISTRIES, 1);
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        var reopened = openStorage();
        OrbitalSavedData loaded = reopened.computeIfAbsent(OrbitalSavedData.FACTORY, OrbitalSavedData.STORAGE_ID);
        assertEquals(100_001, loaded.orbitTicks());
        assertEquals(5_000.05, loaded.orbitSeconds(), 0);
        assertFalse(loaded.isDirty());
        assertEquals(starSystem, loaded.starSystem());
        assertEquals(starSystem.moonOrbit(), loaded.system().body(OrbitalSystem.MOON).orbit().elements());
        assertEquals(savedShip, loaded.ship(id));
        OrbitingShip handoff = loaded.remove(id);
        assertEquals(orientation, handoff.orientation());
        assertEquals(angularVelocity, handoff.angularVelocity());
        assertEquals(shipData, handoff.shipData());
        reopened.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertNull(openStorage().computeIfAbsent(OrbitalSavedData.FACTORY, OrbitalSavedData.STORAGE_ID).ship(id));
    }

    @Test
    public void migratesOldDataToM3WithoutChangingTheShipOrbitOrClock() {
        var orbit = new Orbit(OrbitalSystem.MOON, OrbitElements.fromSemiMajorAxis(15_000, 0, 0.2, 0.1, 0, 1, 20));
        var ship = new OrbitingShip(UUID.randomUUID(), orbit, new Quaterniond(), Vec3.ZERO, new CompoundTag());
        var tag = new CompoundTag();
        tag.putLong("orbit_ticks", 2_000);
        tag.put("ships", OrbitingShip.CODEC.listOf().encodeStart(NbtOps.INSTANCE, List.of(ship)).getOrThrow());

        var migrated = OrbitalSavedData.FACTORY.deserializer().apply(tag, REGISTRIES);
        assertTrue(migrated.isDirty());
        assertEquals(StarSystem.Spacing.MEDIUM, migrated.starSystem().spacing());
        assertEquals(StarSystem.Shape.REGULAR, migrated.starSystem().shape());
        var moon = migrated.system().body(OrbitalSystem.MOON).orbit().elements();
        assertEquals(360_000, moon.semiMajorAxis(), 0);
        assertEquals(0, moon.meanAnomalyAtEpoch(), 0);
        assertEquals(2_000, migrated.orbitTicks());
        assertEquals(ship, migrated.ship(ship.id()));
        assertTrue(migrated.save(new CompoundTag(), REGISTRIES).contains("star_system"));
    }

    @Test
    public void queuedManeuverSurvivesRestartAndClearsWhenTheShipHasNoPropulsion() {
        var storage = openStorage();
        OrbitalSavedData data = storage.computeIfAbsent(OrbitalSavedData.FACTORY, OrbitalSavedData.STORAGE_ID);
        UUID id = UUID.randomUUID();
        var orbit = new Orbit(OrbitalSystem.MOTHER_PLANET, OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0));
        var node = new ManeuverNode(12.075, new Vec3(30, 0, 0));
        var req = Optional.of(node);
        var ship = new OrbitingShip(id, orbit, new Quaterniond(), Vec3.ZERO, new CompoundTag());
        data.put(ship);
        assertEquals(OrbitalSavedData.ManeuverResult.INSUFFICIENT_FUEL, data.commandManeuver(id, req, REGISTRIES));
        data.put(ship.withManeuver(req));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        var loaded = openStorage().computeIfAbsent(OrbitalSavedData.FACTORY, OrbitalSavedData.STORAGE_ID);
        assertEquals(req, loaded.ship(id).maneuver());
        var step = OrbitalTimeWarp.stepBefore(loaded.orbitTicks(), 100_000, loaded.nextManeuverSeconds());
        loaded.advanceTicks(REGISTRIES, step);
        assertEquals(12.05, loaded.orbitSeconds(), 0);
        assertEquals(req, loaded.ship(id).maneuver());
        loaded.advanceTicks(REGISTRIES, 1);
        assertTrue(loaded.ship(id).maneuver().isEmpty());
        assertEquals(orbit, loaded.ship(id).orbit());
    }

    @Test
    public void reportsBothSoiCrossingsOfAFlybyInOneAdvance() {
        var data = new OrbitalSavedData(StarSystem.LEGACY);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var shipData = new CompoundTag();
        shipData.putUUID("ShipOwner", owner);
        var approach = new Orbit(OrbitalSystem.MOTHER_PLANET, OrbitElements.fromSemiMajorAxis(
                345_000, 0, Math.PI, 0, 0, -0.5, 0));
        data.put(new OrbitingShip(id, approach, new Quaterniond(), Vec3.ZERO, shipData));

        var crossings = data.advanceTicks(REGISTRIES, 1_000 * OrbitalSavedData.TICKS_PER_SECOND);
        assertEquals(2, crossings.size());
        for (var crossing : crossings) {
            assertEquals(ShipOrbitEvent.Kind.SOI_CHANGED, crossing.kind());
            assertEquals(id, crossing.shipId());
            assertEquals(owner, crossing.ownerId());
        }
        var arrival = crossings.getFirst();
        var departure = crossings.getLast();
        assertEquals(OrbitalSystem.MOTHER_PLANET, arrival.previousCenter());
        assertEquals(OrbitalSystem.MOON, arrival.orbit().center());
        assertEquals(OrbitalSystem.MOON, departure.previousCenter());
        assertEquals(OrbitalSystem.MOTHER_PLANET, departure.orbit().center());
        assertTrue(arrival.orbit().elements().epochSeconds() < departure.orbit().elements().epochSeconds());
        assertEquals(departure.orbit(), data.ship(id).orbit());
        assertTrue(data.advanceTicks(REGISTRIES, 1).isEmpty());
    }

    private DimensionDataStorage openStorage() {
        return new DimensionDataStorage(worldData.getRoot(), DataFixers.getDataFixer(), REGISTRIES);
    }
}
