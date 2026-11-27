package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
        OrbitalSavedData data = storage.computeIfAbsent(OrbitalSavedData.FACTORY, OrbitalSavedData.STORAGE_ID);
        UUID id = UUID.randomUUID();
        var orientation = new Quaterniond().rotateXYZ(0.2, -0.7, 0.4);
        var angularVelocity = new Vec3(0.11, -0.04, 0.23);
        var shipData = new CompoundTag();
        shipData.putDouble("remaining_fuel", 14.25);
        var savedShip = new OrbitingShip(id, new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0)), orientation, angularVelocity, shipData);
        data.put(savedShip);
        for (int tick = 0; tick < 45; tick++) {
            data.advanceTick(REGISTRIES);
        }
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        var reopened = openStorage();
        OrbitalSavedData loaded = reopened.computeIfAbsent(OrbitalSavedData.FACTORY, OrbitalSavedData.STORAGE_ID);
        assertEquals(45, loaded.orbitTicks());
        assertEquals(2.25, loaded.orbitSeconds(), 0);
        assertFalse(loaded.isDirty());
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
    public void queuedManeuverSurvivesRestartAndClearsWhenTheShipHasNoPropulsion() {
        var storage = openStorage();
        OrbitalSavedData data = storage.computeIfAbsent(OrbitalSavedData.FACTORY, OrbitalSavedData.STORAGE_ID);
        UUID id = UUID.randomUUID();
        var orbit = new Orbit(OrbitalSystem.MOTHER_PLANET, OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0));
        var node = new ManeuverNode(0.075, new Vec3(30, 0, 0));
        var req = Optional.of(node);
        var ship = new OrbitingShip(id, orbit, new Quaterniond(), Vec3.ZERO, new CompoundTag());
        data.put(ship);
        assertEquals(OrbitalSavedData.ManeuverResult.INSUFFICIENT_FUEL, data.commandManeuver(id, req, REGISTRIES));
        data.put(ship.withManeuver(req));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        var loaded = openStorage().computeIfAbsent(OrbitalSavedData.FACTORY, OrbitalSavedData.STORAGE_ID);
        assertEquals(req, loaded.ship(id).maneuver());
        loaded.advanceTick(REGISTRIES);
        assertEquals(req, loaded.ship(id).maneuver());
        loaded.advanceTick(REGISTRIES);
        assertTrue(loaded.ship(id).maneuver().isEmpty());
        assertEquals(orbit, loaded.ship(id).orbit());
    }

    private DimensionDataStorage openStorage() {
        return new DimensionDataStorage(worldData.getRoot(), DataFixers.getDataFixer(), REGISTRIES);
    }
}
