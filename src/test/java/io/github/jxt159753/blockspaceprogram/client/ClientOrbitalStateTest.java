package io.github.jxt159753.blockspaceprogram.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClientOrbitalStateTest {
    @Test
    public void renderingAccountsForOneWayTransitTime() {
        assertEquals(10.225, ClientOrbitalState.interpolatedSeconds(200, 2, 0.5F, 200), 1e-9);
        assertEquals(10.125, ClientOrbitalState.interpolatedSeconds(200, 2, 0.5F, 0), 1e-9);
    }

    @Test
    public void clockExtrapolationIsBoundedWhenUpdatesStall() {
        assertEquals(12, ClientOrbitalState.interpolatedSeconds(200, 200, 0.5F, 5000), 1e-9);
    }
}
