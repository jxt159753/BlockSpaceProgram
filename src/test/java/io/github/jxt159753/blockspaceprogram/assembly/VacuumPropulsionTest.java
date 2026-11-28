package io.github.jxt159753.blockspaceprogram.assembly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion.Drive;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion.Group;
import java.util.List;
import org.junit.Test;

public class VacuumPropulsionTest {
    @Test
    public void estimatesAcrossDepletionOfOneFuelGroup() {
        var propulsion = new VacuumPropulsion(1_000, 100, 0, List.of(
                new Group(0, 10, new Drive(2_000, 0, 0, 10), Drive.OFF, 0),
                new Group(1, 90, new Drive(9_000, 0, 0, 30), Drive.OFF, 0)));
        double afterFirst = 275 * Math.log(1000.0 / 960);
        double deltaV = afterFirst + 300 * Math.log(960.0 / 930);
        var burn = propulsion.estimate(deltaV);
        assertTrue(burn.supplies(deltaV));
        assertEquals(2, burn.seconds(), 1e-10);
        assertEquals(70, burn.fuel(), 1e-10);
        assertEquals(0, burn.fuelFraction(0), 0);
        assertEquals(1.0 / 3, burn.fuelFraction(1), 1e-10);
        assertFalse(propulsion.estimate(200).supplies(200));
    }

    @Test
    public void mixedStageUsesFuelAndElectricity() {
        var chemical = new Drive(20_000, 0, 0, 20_000 / (VacuumPropulsion.STANDARD_GRAVITY * 250));
        var electric = new Drive(4_000, 0, 0, 4_000 / (VacuumPropulsion.STANDARD_GRAVITY * 2_000));
        var group = new Group(0, 100, chemical, electric, 50_000_000);
        var propulsion = new VacuumPropulsion(1_100, 100, 250_000_000, List.of(group));
        var burn = propulsion.estimate(Double.POSITIVE_INFINITY);
        assertEquals(24_000, propulsion.netThrust(), 0);
        assertEquals(249.8877036897, burn.deltaV(), 1e-9);
        assertEquals(12.1333125, burn.seconds(), 1e-9);
        assertEquals(100, burn.fuel(), 1e-9);
        assertEquals(250_000_000, burn.electric(), 0);
        assertEquals(0, burn.remainingElectric(), 0);
        assertEquals(0, burn.fuelFraction(0), 0);
        assertEquals(20_000, new VacuumPropulsion(1_100, 100, 0, List.of(group)).netThrust(), 0);
    }

    @Test
    public void segmentsContinueOnChemicalThrustAfterTheBatteryAndStopWhenFuelRunsOut() {
        var chemical = new Drive(20, 0, 0, 1);
        var electric = new Drive(4, 0, 0, 0.25);
        var propulsion = new VacuumPropulsion(10, 1, 0.75, List.of(new Group(0, 1, chemical, electric, 100)));
        var burn = propulsion.startBurn();
        var powered = burn.nextSegment(Double.POSITIVE_INFINITY, 0.05);
        assertEquals(0.0075, powered.seconds(), 1e-12);
        burn.consume(powered);
        assertEquals(0, burn.remainingElectric(), 0);
        assertTrue(burn.outOfElectricity());
        var chemicalOnly = burn.nextSegment(Double.POSITIVE_INFINITY, 0.05 - powered.seconds());
        assertEquals(0.0425, chemicalOnly.seconds(), 1e-12);
        double mass = 10 - 1.25 * powered.seconds();
        assertEquals(20 * Math.log(mass / (mass - 0.0425)), chemicalOnly.deltaV(), 1e-12);
        burn.consume(chemicalOnly);
        burn.consume(burn.nextSegment(Double.POSITIVE_INFINITY, 2));
        assertEquals(0, burn.remainingFuel(), 1e-12);
        assertEquals(0, burn.nextSegment(Double.POSITIVE_INFINITY, 2).seconds(), 0);
        assertFalse(burn.outOfElectricity());
    }

    @Test
    public void groupWithoutFuelStopsDrawingPowerAndElectricityCanLimitTheManeuver() {
        var electric = new Drive(1_000, 0, 0, 1);
        var propulsion = new VacuumPropulsion(120, 11, 100, List.of(
                new Group(0, 1, Drive.OFF, electric, 40),
                new Group(1, 10, Drive.OFF, electric, 40)));
        var burn = propulsion.estimate(Double.POSITIVE_INFINITY);
        assertEquals(1.5, burn.seconds(), 0);
        assertEquals(2.5, burn.fuel(), 0);
        assertEquals(100, burn.electric(), 0);
        assertEquals(0.85, burn.fuelFraction(1), 1e-12);
        assertFalse(propulsion.estimate(30).supplies(30));
        assertTrue(propulsion.electricityLimits(30));
        assertFalse(propulsion.electricityLimits(200));
    }

    @Test
    public void stageEndsWhenTheSeparatingEnginesLosePower() {
        var core = new Group(0, 50, new Drive(1_000, 0, 0, 1), Drive.OFF, 0);
        var booster = new Group(1, 50, Drive.OFF, new Drive(1_000, 0, 0, 1), 10);
        var propulsion = new VacuumPropulsion(300, 100, 20, List.of(core, booster));
        var burn = propulsion.burnStage(new boolean[] {false, true});
        assertEquals(2, burn.seconds(), 0);
        assertEquals(0, burn.remainingElectric(), 0);
        assertEquals(0.96, burn.fuelFraction(0), 1e-12);
        assertEquals(0.96, burn.fuelFraction(1), 1e-12);
    }
}
