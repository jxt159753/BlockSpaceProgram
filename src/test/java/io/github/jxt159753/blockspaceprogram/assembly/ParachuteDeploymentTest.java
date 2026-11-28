package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.part.ParachutePerformance;
import org.junit.Test;

import static io.github.jxt159753.blockspaceprogram.assembly.ParachuteDeployment.Phase.*;
import static org.junit.Assert.assertEquals;

public class ParachuteDeploymentTest {
    private static final ParachutePerformance PERFORMANCE = new ParachutePerformance(20, 400, 0.02, 250, 500, 2);

    @Test
    public void predictsTerminalSpeedFromDragArea() {
        assertEquals(6.325, ParachuteDeployment.terminalSpeed(1_000, 9.8, 1.225, 400), 0.0005);
        assertEquals(28.284, ParachuteDeployment.terminalSpeed(1_000, 9.8, 1.225, 20), 0.0005);
    }

    @Test
    public void armedChuteWaitsForDensityAndSpeedThenUsesGroundClearance() {
        var state = ParachuteDeployment.STOWED.advance(PERFORMANCE, false, false, 0.03, 200, 400, 2);
        assertEquals(STOWED, state.phase());
        state = state.advance(PERFORMANCE, true, false, 0.01, 200, 600, 2);
        assertEquals(ARMED, state.phase());
        assertEquals(0, state.dragArea(PERFORMANCE), 0);
        state = state.advance(PERFORMANCE, true, false, 0.03, 300, 600, 2);
        assertEquals(ARMED, state.phase());
        state = state.advance(PERFORMANCE, true, false, 0.03, 200, 600, 2);
        assertEquals(SEMI_OPEN, state.phase());
        state = state.advance(PERFORMANCE, true, false, 0.03, 80, 501, 2);
        assertEquals(SEMI_OPEN, state.phase());
        state = state.advance(PERFORMANCE, true, false, 0.03, 80, 400, 2);
        assertEquals(FULL_OPEN, state.phase());
        state = state.advance(PERFORMANCE, true, true, 0.03, 20, 300, 2);
        assertEquals(STOWED, state.phase());
        assertEquals(0, state.dragArea(PERFORMANCE), 0);
    }

    @Test
    public void bothOpeningPhasesIncreaseAreaLinearly() {
        var state = ParachuteDeployment.STOWED.advance(PERFORMANCE, true, false, 0.02, 250, 400, 1);
        assertEquals(10, state.dragArea(PERFORMANCE), 0);
        state = state.advance(PERFORMANCE, true, false, 0.02, 250, 400, 1);
        assertEquals(20, state.dragArea(PERFORMANCE), 0);
        state = state.advance(PERFORMANCE, true, false, 0.02, 250, 400, 1);
        assertEquals(210, state.dragArea(PERFORMANCE), 0);
        assertEquals(FULL_OPEN, ParachuteDeployment.phase(state.pack()));
        assertEquals(210, ParachuteDeployment.dragArea(PERFORMANCE, state.pack()), 380.0 / 63);
        state = state.advance(PERFORMANCE, true, false, 0.02, 250, 400, 1);
        assertEquals(400, state.dragArea(PERFORMANCE), 0);
    }

    @Test
    public void vacuumAndRecoveryKeepTheCanopyStowed() {
        var profile = new ParachutePerformance(20, 400, 0, 250, 500, 2);
        var state = ParachuteDeployment.STOWED.advance(profile, true, false, 0, 50, 100, 2);
        assertEquals(ARMED, state.phase());
        assertEquals(0, state.dragArea(profile), 0);
        var landed = ParachuteDeployment.RECOVERED.advance(profile, true, false, 1, 10, 0, 2);
        assertEquals(STOWED, landed.phase());
        assertEquals(0, landed.dragArea(profile), 0);
    }
}
