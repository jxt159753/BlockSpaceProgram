package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Experimental allocation in ship-local axes, in newtons, newton-metres and kg/s. */
final class RcsAllocation {
    private static final double TORQUE_WEIGHT = 4.0;
    private static final double FUEL_WEIGHT = 0.0001;
    private static final int ITERATIONS = 128;

    enum Method { PROJECTION, LEAST_SQUARES }

    record Jet(Vector3dc force, Vector3dc torque, double fuelFlow) {}

    record Result(double[] openings, Vector3dc wheelTorque, Vector3dc force, Vector3dc torque, double fuelFlow) {}

    private RcsAllocation() {}

    static Result allocate(Method method, List<Jet> jets, Vector3dc force, Vector3dc torque, double wheelLimit) {
        var wheel = limit(new Vector3d(torque), wheelLimit);
        if (jets.isEmpty() || (force.lengthSquared() == 0 && wheel.equals(torque))) {
            return new Result(new double[jets.size()], wheel, new Vector3d(), wheel, 0);
        }
        double[] openings = project(jets, force, torque.sub(wheel, new Vector3d()));
        if (method == Method.LEAST_SQUARES) {
            solve(jets, force, torque, wheelLimit, openings);
        }
        var forceSum = new Vector3d();
        var jetTorque = new Vector3d();
        double fuel = 0;
        for (int i = 0; i < jets.size(); i++) {
            var jet = jets.get(i);
            forceSum.fma(openings[i], jet.force);
            jetTorque.fma(openings[i], jet.torque);
            fuel += openings[i] * jet.fuelFlow;
        }
        if (method == Method.LEAST_SQUARES) {
            limit(wheel.set(torque).sub(jetTorque), wheelLimit);
        }
        return new Result(openings, wheel, forceSum, jetTorque.add(wheel), fuel);
    }

    private static double[] project(List<Jet> jets, Vector3dc force, Vector3dc torque) {
        double forceScale = positiveCapacity(jets, force, false);
        double torqueScale = positiveCapacity(jets, torque, true);
        double[] openings = new double[jets.size()];
        for (int i = 0; i < openings.length; i++) {
            var jet = jets.get(i);
            double linear = jet.force.dot(force) / (jet.force.length() * forceScale);
            double angular = jet.torque.lengthSquared() == 0 ? 0
                    : jet.torque.dot(torque) / (jet.torque.length() * torqueScale);
            openings[i] = unit(linear + angular);
        }
        return openings;
    }

    private static double positiveCapacity(List<Jet> jets, Vector3dc request, boolean torque) {
        if (request.lengthSquared() == 0) {
            return 1;
        }
        double capacity = 0;
        for (var jet : jets) {
            capacity += Math.max(0, (torque ? jet.torque : jet.force).dot(request));
        }
        return Math.max(1, capacity / request.length());
    }

    private static void solve(List<Jet> jets, Vector3dc force, Vector3dc torque, double wheelLimit, double[] openings) {
        // The squared column norms bound the gradient's Lipschitz constant.
        double curvatureBound = 0;
        double maxForceSquared = 0;
        double maxFuelFlow = 0;
        for (var jet : jets) {
            double forceSquared = jet.force.lengthSquared();
            curvatureBound += forceSquared + TORQUE_WEIGHT * jet.torque.lengthSquared();
            maxForceSquared = Math.max(maxForceSquared, forceSquared);
            maxFuelFlow = Math.max(maxFuelFlow, jet.fuelFlow);
        }
        double step = 1 / curvatureBound;
        double fuelPenalty = maxFuelFlow == 0 ? 0 : FUEL_WEIGHT * maxForceSquared / maxFuelFlow;
        // Start with the projected command so readily usable translation does not have to be rediscovered.
        double[] extrapolated = openings.clone();
        var forceError = new Vector3d();
        var torqueError = new Vector3d();
        var wheel = new Vector3d();
        double acceleration = 1;
        int stableSteps = 0;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            forceError.set(force).negate();
            torqueError.set(torque).negate();
            for (int i = 0; i < jets.size(); i++) {
                var jet = jets.get(i);
                forceError.fma(extrapolated[i], jet.force);
                torqueError.fma(extrapolated[i], jet.torque);
            }
            // For any jet settings the optimal free wheel torque is the missing torque, clipped to its capacity.
            torqueError.add(limit(wheel.set(torqueError).negate(), wheelLimit)).mul(TORQUE_WEIGHT);
            double nextAcceleration = (1 + Math.sqrt(1 + 4 * acceleration * acceleration)) / 2;
            double momentum = (acceleration - 1) / nextAcceleration;
            double largestChange = 0;
            for (int i = 0; i < jets.size(); i++) {
                var jet = jets.get(i);
                double gradient = jet.force.dot(forceError) + jet.torque.dot(torqueError) + fuelPenalty * jet.fuelFlow;
                double next = unit(extrapolated[i] - step * gradient);
                double change = next - openings[i];
                extrapolated[i] = next + momentum * change;
                openings[i] = next;
                largestChange = Math.max(largestChange, Math.abs(change));
            }
            acceleration = nextAcceleration;
            stableSteps = largestChange < 1e-6 ? stableSteps + 1 : 0;
            if (stableSteps == 4) {
                break;
            }
        }
    }

    private static double unit(double value) {
        return Math.clamp(value, 0, 1);
    }

    private static Vector3d limit(Vector3d vector, double max) {
        double length = vector.length();
        return length > max ? vector.mul(max / length) : vector;
    }
}
