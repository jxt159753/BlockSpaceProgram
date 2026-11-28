package io.github.jxt159753.blockspaceprogram.experiment.propellant;

import static org.junit.Assert.assertEquals;

import io.github.jxt159753.blockspaceprogram.experiment.propellant.PropellantBurn.Engine;
import io.github.jxt159753.blockspaceprogram.experiment.propellant.PropellantBurn.Supply;
import org.junit.Test;

public class PropellantBurnTest {
    private static final Engine CHEMICAL = new Engine(20_000, 250);
    private static final Engine ELECTRIC = new Engine(4_000, 2_000);
    private static final double POWER = 50_000_000;
    private static final Supply FULL = new Supply(1_000, 100, 250_000_000);

    @Test
    public void mixedStageUsesElectricityOnlyForElectricEngine() {
        var burn = new PropellantBurn(CHEMICAL, ELECTRIC, POWER, false).advance(FULL, Double.POSITIVE_INFINITY);
        assertEquals(252.1958801401, burn.deltaV(), 1e-9);
        assertEquals(12.2583125, burn.seconds(), 1e-9);
        assertEquals(5, burn.electricSeconds(), 0);
        assertEquals(new Supply(1_000, 0, 0), burn.remaining());
    }

    @Test
    public void electricityOnlyEngineStillProducesDeltaVAtConstantMass() {
        var burn = new PropellantBurn(Engine.OFF, ELECTRIC, POWER, false)
                .advance(new Supply(1_000, 0, FULL.energy()), Double.POSITIVE_INFINITY);
        assertEquals(20, burn.deltaV(), 0);
        assertEquals(5, burn.seconds(), 0);
        assertEquals(new Supply(1_000, 0, 0), burn.remaining());
    }
}
