package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Temporary mounts on a fixture ship. Ratings are set by the experiment instead of baked into saved parts. */
public final class RcsFlightTrial {
    record Mount(int part, Vector3dc point, Vector3dc force, double isp, double electricPower) {}

    record Thruster(int part, int group, Vector3dc force, Vector3dc originMoment, double fuelFlow, double electricPower) {}

    record Firing(Thruster thruster, double opening) {}

    record Command(List<Firing> firings, Vector3dc wheel) {}

    private final RcsAllocation.Method method;
    private final List<Thruster> thrusters;
    private final double translationScale;
    private boolean enabled;
    private Vec3 translation = Vec3.ZERO;
    private Command lastCommand;
    private long solvedAt = Long.MIN_VALUE;
    private long solveNanos;

    RcsFlightTrial(ShipDesign design, RcsAllocation.Method method, List<Mount> mounts) {
        this.method = method;
        int[] groups = StagePerformance.tankGroups(design);
        var capacity = new double[6];
        var jets = new ArrayList<Thruster>(mounts.size());
        for (var mount : mounts) {
            var force = mount.force;
            jets.add(new Thruster(mount.part, groups[mount.part], force, mount.point.cross(force, new Vector3d()),
                    StagePerformance.fuelFlow(force.length(), mount.isp), mount.electricPower));
            capacity[0] += Math.max(0, force.x());
            capacity[1] += Math.max(0, -force.x());
            capacity[2] += Math.max(0, force.y());
            capacity[3] += Math.max(0, -force.y());
            capacity[4] += Math.max(0, force.z());
            capacity[5] += Math.max(0, -force.z());
        }
        thrusters = List.copyOf(jets);
        translationScale = java.util.Arrays.stream(capacity).max().orElse(0);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setTranslation(double x, double y, double z) {
        translation = new Vec3(x, y, z);
    }

    public Vec3 translation() {
        return enabled ? translation : Vec3.ZERO;
    }

    long solveNanos(long gameTime) {
        return solvedAt == gameTime ? solveNanos : 0;
    }

    Command allocate(long gameTime, Vector3dc center, Quaterniondc pilotRotation, Vector3dc torque, double wheelLimit,
            ShipFlightState state) {
        if (solvedAt == gameTime) {
            return lastCommand;
        }
        var force = pilotRotation.transform(new Vector3d(translation.x, translation.y, translation.z)).mul(translationScale);
        double charge = state.remainingElectric();
        state.collectGroupFuel();
        var fed = new ArrayList<Thruster>(thrusters.size());
        var jets = new ArrayList<RcsAllocation.Jet>(thrusters.size());
        for (var thruster : thrusters) {
            if (state.canFireRcs(thruster, charge)) {
                fed.add(thruster);
                jets.add(new RcsAllocation.Jet(thruster.force,
                        center.cross(thruster.force, new Vector3d()).negate().add(thruster.originMoment), thruster.fuelFlow));
            }
        }
        long start = System.nanoTime();
        var allocation = RcsAllocation.allocate(method, jets, force, torque, wheelLimit);
        solveNanos = System.nanoTime() - start;
        var firings = new ArrayList<Firing>();
        for (int i = 0; i < fed.size(); i++) {
            double opening = allocation.openings()[i];
            if (opening > 0) {
                firings.add(new Firing(fed.get(i), opening));
            }
        }
        solvedAt = gameTime;
        lastCommand = new Command(firings, allocation.wheelTorque());
        return lastCommand;
    }
}
