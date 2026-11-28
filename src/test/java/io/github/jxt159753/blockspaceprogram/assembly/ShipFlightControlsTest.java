package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.concurrent.atomic.AtomicBoolean;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.Test;

import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.AttitudeMode.HOLD;
import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.AttitudeMode.PROGRADE;
import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.AttitudeMode.RETROGRADE;
import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.ThrottleMode.*;
import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightInput.*;
import static org.junit.Assert.*;

public class ShipFlightControlsTest {
    @Test
    public void pilotArbitratesOnlyTheAxesWithKeysDown() {
        var controls = new ShipFlightControls();
        var orientation = new Quaterniond();
        var still = new Vector3d();
        controls.setAxes(0.3, 0.4, 0.5);

        var pitchOverride = controls.resolveAxes(PITCH_UP, orientation, still, still);
        assertEquals(-1, pitchOverride.x(), 0);
        assertEquals(0.4, pitchOverride.y(), 0);
        assertEquals(0.5, pitchOverride.z(), 0);
        assertEquals(0.3, controls.resolveAxes(0, orientation, still, still).x(), 0);
        assertEquals(0, controls.resolveAxes(PITCH_UP | PITCH_DOWN, orientation, still, still).x(), 0);
    }

    @Test
    public void pilotArbitratesTranslationOneAxisAtATime() {
        var controls = new ShipFlightControls();
        controls.setRcs(true);
        controls.setTranslation(0.35, -0.25, 0.5);

        var steered = controls.resolveTranslation(TRANSLATE_LEFT | TRANSLATE_FORWARD);
        assertEquals(-1, steered.x, 0);
        assertEquals(-0.25, steered.y, 0);
        assertEquals(-1, steered.z, 0);
        assertEquals(1, controls.resolveTranslation(TRANSLATE_UP).y, 0);
        assertEquals(0, controls.resolveTranslation(TRANSLATE_LEFT | TRANSLATE_RIGHT).x, 0);
        assertEquals(0.35, controls.resolveTranslation(0).x, 0);
    }

    @Test
    public void disablingRcsClearsTranslationUntilNewInputArrives() {
        var controls = new ShipFlightControls();
        controls.setRcs(true);
        controls.setTranslation(0.35, -0.25, 0.5);
        controls.setRcs(false);

        assertFalse(controls.rcsEnabled());
        assertEquals(0, controls.resolveTranslation(TRANSLATE_RIGHT).lengthSquared(), 0);
        controls.setTranslation(1, 1, 1);
        controls.setRcs(true);
        assertEquals(0, controls.resolveTranslation(0).lengthSquared(), 0);
    }

    @Test
    public void releaseAndConnectionLossClearRemoteTranslation() {
        var controls = new ShipFlightControls();
        var heading = new Quaterniond();
        var connected = new AtomicBoolean(true);
        controls.connect(connected::get);
        controls.setRcs(true);
        controls.setTranslation(0.35, 0, 0);

        assertFalse(controls.releaseRemote(() -> true, heading));
        assertEquals(0.35, controls.resolveTranslation(0).x, 0);
        assertTrue(controls.releaseRemote(controls.remote(), heading));
        assertEquals(0, controls.resolveTranslation(0).lengthSquared(), 0);
        assertEquals(1, controls.resolveTranslation(TRANSLATE_RIGHT).x, 0);

        controls.connect(connected::get);
        controls.setTranslation(-1, 1, 0);
        connected.set(false);
        assertTrue(controls.checkConnection(heading));
        assertEquals(0, controls.resolveTranslation(0).lengthSquared(), 0);
    }

    @Test
    public void pilotThrottleOverrideResumesAutomationAndCutoffCancelsIt() {
        var controls = new ShipFlightControls();
        controls.setThrottle(DIRECT, 0.6);
        assertEquals(0.6, controls.resolveThrottle(0, 0, 0, 0, 0), 0);
        controls.tickPilot(THROTTLE_DOWN);
        assertEquals(0.55, controls.resolveThrottle(THROTTLE_DOWN, 0, 0, 0, 0), 1e-9);
        controls.clearPilot();
        assertEquals(0.6, controls.resolveThrottle(0, 0, 0, 0, 0), 0);
        assertEquals(0, controls.resolveThrottle(CUTOFF, 0, 0, 0, 0), 0);
        assertEquals(MANUAL, controls.throttleMode());
        assertEquals(0, controls.resolveThrottle(0, 0, 0, 0, 0), 0);
    }

    @Test
    public void remoteLossCutsThrustAndCapturesCurrentHeading() {
        var controls = new ShipFlightControls();
        var connected = new AtomicBoolean(true);
        var heading = ShipFlightControls.heading(15, 5, -8);
        var still = new Vector3d();
        controls.connect(connected::get);
        controls.setAxes(1, 0, 0);
        controls.setThrottle(DIRECT, 0.8);
        assertFalse(controls.checkConnection(heading));

        connected.set(false);
        assertTrue(controls.checkConnection(heading));
        assertEquals(0, controls.resolveThrottle(0, 0, 0, 0, 0), 0);
        assertEquals(HOLD, controls.attitudeMode());
        assertEquals(0, controls.resolveAxes(0, heading, still, still).lengthSquared(), 0);
        var disturbed = new Quaterniond(heading).rotateX(0.1);
        assertTrue(controls.resolveAxes(0, disturbed, still, still).x() < 0);
    }

    @Test
    public void handoffKeepsPilotThrottleAndRotatesHeldHeading() {
        var source = new ShipFlightControls();
        var heading = ShipFlightControls.heading(15, 5, -8);
        source.setAttitudeMode(HOLD, heading);
        source.tickPilot(THROTTLE_UP);
        var rotation = new Quaterniond().rotationY(1.2);
        var targetHeading = new Quaterniond(rotation).mul(heading);
        var target = new ShipFlightControls();
        target.inheritForHandoff(source, rotation, targetHeading);

        assertEquals(HOLD, target.attitudeMode());
        assertEquals(0.05, target.resolveThrottle(0, 0, 0, 0, 0), 1e-9);
        var still = new Vector3d();
        assertEquals(0, target.resolveAxes(0, targetHeading, still, still).lengthSquared(), 0);
        var disturbed = new Quaterniond(targetHeading).rotateX(0.1);
        assertTrue(target.resolveAxes(0, disturbed, still, still).x() < 0);
    }

    @Test
    public void handoffKeepsTheRemoteControllerAndItsSetpoints() {
        var source = new ShipFlightControls();
        var connected = new AtomicBoolean(true);
        source.connect(connected::get);
        source.setThrottle(DIRECT, 0.8);
        source.setAttitudeMode(PROGRADE, new Quaterniond());
        var rotation = new Quaterniond().rotationY(1.2);
        var arrivalAttitude = new Quaterniond().rotationX(0.7);
        var onRails = new ShipFlightControls();
        onRails.inheritForHandoff(source, rotation, arrivalAttitude);

        assertSame(source.remote(), onRails.remote());
        assertEquals(PROGRADE, onRails.attitudeMode());
        assertEquals(DIRECT, onRails.throttleMode());
        assertEquals(0.8, onRails.resolveThrottle(0, 0, 0, 0, 0), 0);

        connected.set(false);
        var target = new ShipFlightControls();
        target.inheritForHandoff(onRails, new Quaterniond(), arrivalAttitude);
        assertNull(target.remote());
        assertEquals(MANUAL, target.throttleMode());
        assertEquals(0, target.resolveThrottle(0, 0, 0, 0, 0), 0);
        assertEquals(HOLD, target.attitudeMode());
        var still = new Vector3d();
        assertEquals(0, target.resolveAxes(0, arrivalAttitude, still, still).lengthSquared(), 0);
    }

    @Test
    public void throttleCompensatesForGravityAndDragAndSaturatesAtAvailableThrust() {
        var controls = new ShipFlightControls();
        controls.setThrottle(VERTICAL_SPEED, 20);
        assertEquals(0.6, controls.resolveThrottle(0, 20, 12, 20, 2), 1e-9);
        assertEquals(1, controls.resolveThrottle(0, 0, 12, 20, 2), 0);
        assertEquals(0, controls.resolveThrottle(0, 40, 12, 20, 2), 0);
        assertEquals(0, controls.resolveThrottle(0, 0, 12, -5, 2), 0);

        controls.setThrottle(THRUST_TO_WEIGHT, 1.5);
        assertEquals(0.75, controls.resolveThrottle(0, 0, 0, 0, 2), 1e-9);
        assertEquals(0, controls.resolveThrottle(0, 0, 0, 0, 0), 0);
    }

    @Test
    public void velocityTargetsPointTheNoseInOppositeDirections() {
        var controls = new ShipFlightControls();
        var orientation = new Quaterniond();
        var velocity = new Vector3d(1, 0, 0);
        var still = new Vector3d();
        controls.setAttitudeMode(PROGRADE, orientation);
        assertTrue(controls.resolveAxes(0, orientation, velocity, still).z() < 0);
        assertTrue(controls.resolveAxes(0, orientation, still, still).z() < 0);
        controls.setAttitudeMode(RETROGRADE, orientation);
        assertTrue(controls.resolveAxes(0, orientation, velocity, still).z() > 0);
    }
}
