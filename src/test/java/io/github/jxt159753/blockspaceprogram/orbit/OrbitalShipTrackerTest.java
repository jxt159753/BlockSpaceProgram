package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.junit.Test;

public class OrbitalShipTrackerTest {
    private final OrbitalSavedData data = new OrbitalSavedData(StarSystem.LEGACY);
    private final UUID player = UUID.randomUUID();
    private final OrbitalShipTracker tracker = new OrbitalShipTracker();
    private final Map<UUID, OrbitingShip> sent = new HashMap<>();

    @Test
    public void dimensionChangesReplaceOtherShipsButRetainOwnedShips() {
        var earth = ship(OrbitalSystem.MOTHER_PLANET, 70_000, UUID.randomUUID());
        var moon = ship(OrbitalSystem.MOON, 17_000, UUID.randomUUID());
        var owned = ship(OrbitalSystem.MOON, 18_000, player);
        data.put(earth);
        data.put(moon);
        data.put(owned);

        sync(OrbitalSystem.MOTHER_PLANET, false);
        assertEquals(Set.of(earth.id(), owned.id()), sent.keySet());

        sync(OrbitalSystem.MOON, false);
        assertEquals(Set.of(earth.id(), moon.id()), sent.keySet());
        assertNull(sent.get(earth.id()));
        assertEquals(moon, sent.get(moon.id()));

        sync(OrbitalSystem.MOON, false);
        assertTrue(sent.isEmpty());
    }

    @Test
    public void changedAndRemovedShipsOnlySendToInterestedClients() {
        var moon = ship(OrbitalSystem.MOON, 17_000, UUID.randomUUID());
        sync(OrbitalSystem.MOTHER_PLANET, false);

        data.put(moon);
        sync(OrbitalSystem.MOTHER_PLANET, false);
        assertTrue(sent.isEmpty());

        var earth = moon.withOrbit(orbit(OrbitalSystem.MOTHER_PLANET, 70_000));
        data.put(earth);
        sync(OrbitalSystem.MOTHER_PLANET, false);
        assertEquals(Map.of(earth.id(), earth), sent);

        data.put(moon);
        sync(OrbitalSystem.MOTHER_PLANET, false);
        assertEquals(Set.of(moon.id()), sent.keySet());
        assertNull(sent.get(moon.id()));

        data.remove(moon.id());
        sync(OrbitalSystem.MOTHER_PLANET, false);
        assertTrue(sent.isEmpty());
    }

    @Test
    public void openingTheWorldToLanDropsUnrelatedShips() {
        var earth = ship(OrbitalSystem.MOTHER_PLANET, 70_000, UUID.randomUUID());
        var moon = ship(OrbitalSystem.MOON, 17_000, UUID.randomUUID());
        data.put(earth);
        data.put(moon);
        sync(OrbitalSystem.MOTHER_PLANET, true);
        assertEquals(Set.of(earth.id(), moon.id()), sent.keySet());

        sync(OrbitalSystem.MOTHER_PLANET, false);
        assertEquals(Set.of(moon.id()), sent.keySet());
        assertNull(sent.get(moon.id()));

        data.remove(earth.id());
        sync(OrbitalSystem.MOTHER_PLANET, false);
        assertEquals(Set.of(earth.id()), sent.keySet());
        assertNull(sent.get(earth.id()));
    }

    private void sync(ResourceLocation center, boolean showAll) {
        sent.clear();
        tracker.sync(data, new OrbitalShipTracker.Interest(player, center, showAll), data.takeChanges(), sent::put);
    }

    private static OrbitingShip ship(ResourceLocation center, double radius, UUID owner) {
        var tag = new CompoundTag();
        tag.putUUID(AssembledShipEntity.SAVED_OWNER_TAG, owner);
        return new OrbitingShip(UUID.randomUUID(), orbit(center, radius), new Quaterniond(), Vec3.ZERO, tag);
    }

    private static Orbit orbit(ResourceLocation center, double radius) {
        return new Orbit(center, OrbitElements.fromSemiMajorAxis(radius, 0, 0, 0, 0, 0, 0));
    }
}
