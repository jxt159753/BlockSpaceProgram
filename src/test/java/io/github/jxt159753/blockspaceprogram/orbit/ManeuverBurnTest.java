package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion.Drive;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion.Group;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.junit.Test;

public class ManeuverBurnTest {
    private static final OrbitalSystem SYSTEM = OrbitalSystem.legacy();
    private static final ManeuverNode NODE = new ManeuverNode(2.025, new Vec3(10, 0, 0));
    private static final Drive ELECTRIC = new Drive(20, 0, 0, 20 / (9.80665 * 2_000));

    @Test
    public void electricTrialMatchesAtOneAndOneHundredTimesClockRate() {
        var normal = run(1, false, NODE);
        var warped = run(100, false, NODE);
        for (var result : List.of(normal, warped)) {
            assertEquals(OrbitalSavedData.ManeuverResult.INSUFFICIENT_ELECTRICITY, result.outcome().failure());
            assertEquals(2.025, result.outcome().progress().startedAt(), 0);
            assertEquals(120.230, result.seconds(), 1e-8);
            assertEquals(5.952883, result.outcome().progress().deltaV(), 5e-7);
            assertEquals(3.877400, result.fuel(), 5e-7);
            assertEquals(0, result.electric(), 0);
        }
        var cutoff = SYSTEM.relativeState(normal.outcome().orbit(), 122.255);
        assertEquals(0, cutoff.position().distanceTo(new Vec3(102_091.087, 63_310.536, 0)), 0.002);
        assertEquals(0, cutoff.velocity().distanceTo(new Vec3(-285.894, 467.040, 0)), 0.002);
        assertSameFlight(normal, warped);
    }

    @Test
    public void resumesTheSameBurnFromSerializedProgressAndStopsAtTheRequestedDeltaV() {
        var node = new ManeuverNode(NODE.seconds(), new Vec3(5, 0, 0));
        var uninterrupted = run(1, false, node);
        var resumed = run(100, true, node);
        assertNull(resumed.outcome().failure());
        assertEquals(5, resumed.outcome().progress().deltaV(), 0);
        assertTrue(resumed.electric() > 0);
        assertSameFlight(uninterrupted, resumed);
    }

    private static Trial run(int rate, boolean restart, ManeuverNode node) {
        var orbit = new Orbit(OrbitalSystem.MOTHER_PLANET, OrbitElements.fromSemiMajorAxis(120_000, 0, 0, 0, 0, 0, 0));
        var ship = new OrbitingShip(UUID.randomUUID(), orbit, new Quaterniond(), Vec3.ZERO, new CompoundTag(),
                Optional.of(node));
        double seconds = 0;
        double fuel = 4;
        double electric = 36_069_000;
        long tick = 0;
        while (tick < 3_000) {
            long nextTick = tick + rate;
            if (nextTick / 20.0 > node.seconds()) {
                var burn = new VacuumPropulsion(400 + fuel, fuel, electric,
                        List.of(new Group(0, fuel, new Drive(0, 0, 0, 0), ELECTRIC, 300_000))).startBurn();
                var outcome = ManeuverBurn.advance(SYSTEM, ship, node, burn, tick, nextTick, SYSTEM::advance);
                assertNotNull(outcome);
                var portion = burn.result();
                seconds += portion.seconds();
                fuel *= portion.remainingFuelFractions().get(0);
                electric = burn.remainingElectric();
                if (outcome.finished()) {
                    return new Trial(outcome, seconds, fuel, electric);
                }
                var resources = new CompoundTag();
                resources.putDouble("fuel", fuel);
                resources.putDouble("electric", electric);
                ship = ship.afterBurn(outcome, resources);
                if (restart && nextTick == 1_200) {
                    var tag = OrbitingShip.CODEC.encodeStart(NbtOps.INSTANCE, ship).getOrThrow();
                    var loaded = OrbitingShip.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
                    assertEquals(outcome.progress(), loaded.maneuverProgress().orElseThrow());
                    ship = loaded;
                    fuel = ship.shipData().getDouble("fuel");
                    electric = ship.shipData().getDouble("electric");
                }
            }
            tick = nextTick;
        }
        throw new AssertionError("Burn did not finish");
    }

    private static void assertSameFlight(Trial expected, Trial actual) {
        assertEquals(expected.seconds(), actual.seconds(), 1e-8);
        assertEquals(expected.outcome().progress().deltaV(), actual.outcome().progress().deltaV(), 1e-8);
        assertEquals(expected.fuel(), actual.fuel(), 1e-10);
        assertEquals(expected.electric(), actual.electric(), 1e-5);
        var left = SYSTEM.relativeState(expected.outcome().orbit(), 125);
        var right = SYSTEM.relativeState(actual.outcome().orbit(), 125);
        assertEquals(0, left.position().distanceTo(right.position()), 1e-6);
        assertEquals(0, left.velocity().distanceTo(right.velocity()), 1e-8);
    }

    private record Trial(ManeuverBurn.Outcome outcome, double seconds, double fuel, double electric) {}
}
