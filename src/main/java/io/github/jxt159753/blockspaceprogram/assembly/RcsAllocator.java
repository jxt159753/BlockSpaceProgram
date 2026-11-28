package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.Arrays;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Bounded allocation in ship-local axes, in newtons, newton-metres and kg/s. */
final class RcsAllocator {
    // Convert torque error to force error at the prototype's reference lever arm of 0.5 m.
    private static final double TORQUE_SCALE = 2;
    private static final double FUEL_WEIGHT = 0.0001;
    private static final double TOLERANCE = 1e-6;
    private static final int ITERATIONS = 384;
    private static final int DIMENSIONS = 6;

    record Jet(Vector3dc arm, Vector3dc direction, double thrust, double fuelFlow) {}

    record Result(double[] openings, Vector3dc wheelTorque, Vector3dc force, Vector3dc torque, double fuelFlow) {}

    private RcsAllocator() {}

    static Result allocate(List<Jet> jets, Vector3dc force, Vector3dc torque, double wheelLimit) {
        var wheel = limit(new Vector3d(torque), wheelLimit);
        if (jets.isEmpty() || (force.lengthSquared() == 0 && torque.lengthSquared() <= wheelLimit * wheelLimit)) {
            return new Result(new double[jets.size()], wheel, new Vector3d(), wheel, 0);
        }
        var problem = new Problem(jets, wheelLimit);
        if (force.lengthSquared() > 0) {
            var along = problem.alongRequest(force, torque);
            if (along != null) {
                return along;
            }
        }
        return problem.solve(force, torque, null);
    }

    static double availableAlong(List<Jet> jets, Vector3dc unit) {
        return jets.stream().mapToDouble(jet -> Math.max(0, jet.direction.dot(unit)) * jet.thrust).sum();
    }

    static double pureThrust(List<Jet> jets, Vector3dc unit, double wheelLimit) {
        double available = availableAlong(jets, unit);
        if (available == 0) {
            return 0;
        }
        var result = new Problem(jets, wheelLimit).alongRequest(unit.mul(available, new Vector3d()), new Vector3d());
        return result == null ? 0 : result.force.dot(unit);
    }

    private static Vector3d limit(Vector3d vector, double radius) {
        double length = vector.length();
        return length > radius ? vector.mul(radius / length) : vector;
    }

    private static final class Problem {
        private final List<Jet> jets;
        private final double scale;
        private final double wheelLimit;
        private final double[] costs;
        private final double[][] columns;
        private final double[][] lower = new double[DIMENSIONS][DIMENSIONS];
        private final double[][] capacity = new double[3][2];

        Problem(List<Jet> jets, double wheelLimit) {
            this.jets = jets;
            this.wheelLimit = wheelLimit;
            scale = jets.stream().mapToDouble(Jet::thrust).max().orElseThrow();
            double maxFuel = jets.stream().mapToDouble(Jet::fuelFlow).max().orElseThrow();
            costs = new double[jets.size()];
            columns = new double[jets.size() + (wheelLimit > 0 ? 3 : 0)][DIMENSIONS];
            for (int i = 0; i < jets.size(); i++) {
                Jet jet = jets.get(i);
                var force = jet.direction.mul(jet.thrust / scale, new Vector3d());
                var torque = jet.arm.cross(force, new Vector3d()).mul(TORQUE_SCALE);
                setWrench(columns[i], force, torque);
                for (int axis = 0; axis < 3; axis++) {
                    double component = force.get(axis);
                    capacity[axis][component < 0 ? 0 : 1] += Math.abs(component);
                }
                costs[i] = maxFuel > 0 ? FUEL_WEIGHT * (jet.fuelFlow / maxFuel) : 0;
            }
            for (int i = jets.size(); i < columns.length; i++) {
                columns[i][3 + i - jets.size()] = 1;
            }
            // Factor I + A A^T once. Its size depends on the wrench, independently of the jet count.
            for (int row = 0; row < DIMENSIONS; row++) {
                for (int col = 0; col <= row; col++) {
                    double value = row == col ? 1 : 0;
                    for (var column : columns) {
                        value += column[row] * column[col];
                    }
                    for (int k = 0; k < col; k++) {
                        value -= lower[row][k] * lower[col][k];
                    }
                    lower[row][col] = row == col ? Math.sqrt(value) : value / lower[col][col];
                }
            }
        }

        Result alongRequest(Vector3dc force, Vector3dc torque) {
            var unit = force.normalize(new Vector3d());
            double distance = force.length();
            // Axis capacities give a cheap upper bound on the length of the requested ray.
            for (int axis = 0; axis < 3; axis++) {
                double component = unit.get(axis);
                if (Math.abs(component) > TOLERANCE) {
                    distance = Math.min(distance, capacity[axis][component < 0 ? 0 : 1] / Math.abs(component) * scale);
                }
            }
            double epsilon = TOLERANCE * scale;
            if (distance <= epsilon) {
                return null;
            }
            var result = solve(unit.mul(distance, new Vector3d()), torque, unit);
            double achieved = result.force.dot(unit);
            if (achieved <= epsilon || result.force.distance(unit.mul(achieved, new Vector3d())) > epsilon
                    || result.torque.distance(torque) > epsilon / TORQUE_SCALE) {
                return null;
            }
            return result;
        }

        /** ADMM alternates the actuator bounds and the desired wrench in their respective spaces. */
        Result solve(Vector3dc force, Vector3dc torque, Vector3dc ray) {
            var target = new double[DIMENSIONS];
            setWrench(target, force.div(scale, new Vector3d()), torque.mul(TORQUE_SCALE / scale, new Vector3d()));
            double distance = force.length() / scale;
            var wrench = target.clone();
            var wrenchDual = new double[DIMENSIONS];
            var bounded = new double[columns.length];
            var actuatorDual = new double[columns.length];
            var free = new double[columns.length];
            var correction = new double[DIMENSIONS];
            var applied = new double[DIMENSIONS];
            double wheelRadius = wheelLimit / scale * TORQUE_SCALE;
            boolean saturated = true;
            for (int i = 0; i < jets.size(); i++) {
                var axis = jets.get(i).direction;
                double projection = 0;
                double available = 0;
                for (int j = 0; j < 3; j++) {
                    double value = axis.get(j);
                    projection += value * target[j];
                    available += Math.abs(value) * capacity[j][value < 0 ? 0 : 1];
                }
                bounded[i] = Math.clamp(projection / available, 0, 1);
                saturated &= bounded[i] == 0 || bounded[i] >= 1 - TOLERANCE;
                addScaled(applied, columns[i], bounded[i]);
            }
            var initialWheel = limit(new Vector3d(target[3] - applied[3], target[4] - applied[4],
                    target[5] - applied[5]), wheelRadius);
            if (wheelRadius > 0) {
                for (int axis = 0; axis < 3; axis++) {
                    bounded[jets.size() + axis] = initialWheel.get(axis);
                    applied[3 + axis] += initialWheel.get(axis);
                }
            }
            // All jets along the demand are at their limits and the wheel can hold the requested torque.
            if (ray != null && saturated && matches(applied, target)) {
                return result(bounded, torque);
            }
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                Arrays.fill(correction, 0);
                for (int i = 0; i < columns.length; i++) {
                    free[i] = bounded[i] - actuatorDual[i];
                    addScaled(correction, columns[i], free[i]);
                }
                for (int axis = 0; axis < DIMENSIONS; axis++) {
                    correction[axis] += wrenchDual[axis] - wrench[axis];
                }
                solveNormal(correction);

                double error = 0;
                Arrays.fill(applied, 0);
                for (int i = 0; i < columns.length; i++) {
                    free[i] -= dot(columns[i], correction);
                    addScaled(applied, columns[i], free[i]);
                    if (i < jets.size()) {
                        double next = Math.clamp(free[i] + actuatorDual[i] - costs[i], 0, 1);
                        error = Math.max(error, updateActuator(i, next, free, bounded, actuatorDual));
                    }
                }
                if (columns.length > jets.size()) {
                    int i = jets.size();
                    var wheel = limit(new Vector3d(free[i] + actuatorDual[i],
                            free[i + 1] + actuatorDual[i + 1], free[i + 2] + actuatorDual[i + 2]), wheelRadius);
                    for (int axis = 0; axis < 3; axis++) {
                        error = Math.max(error, updateActuator(i + axis, wheel.get(axis), free, bounded, actuatorDual));
                    }
                }
                // With a ray constraint, keep the requested torque and only shorten the requested force.
                double along = ray == null ? 0 : Math.clamp((distance
                        + ray.x() * (applied[0] + wrenchDual[0])
                        + ray.y() * (applied[1] + wrenchDual[1])
                        + ray.z() * (applied[2] + wrenchDual[2])) * 0.5, 0, distance);
                for (int axis = 0; axis < DIMENSIONS; axis++) {
                    double next = ray == null ? (target[axis] + applied[axis] + wrenchDual[axis]) * 0.5
                            : axis < 3 ? along * ray.get(axis) : target[axis];
                    double residual = applied[axis] - next;
                    wrenchDual[axis] += residual;
                    error = Math.max(error, Math.max(Math.abs(residual), Math.abs(next - wrench[axis])));
                    wrench[axis] = next;
                }
                if (error < TOLERANCE / 16) {
                    break;
                }
            }
            return result(bounded, torque);
        }

        private Result result(double[] bounded, Vector3dc torque) {
            var openings = Arrays.copyOf(bounded, jets.size());
            var applied = new double[DIMENSIONS];
            double fuel = 0;
            for (int i = 0; i < openings.length; i++) {
                addScaled(applied, columns[i], openings[i]);
                fuel += jets.get(i).fuelFlow * openings[i];
            }
            var actualForce = new Vector3d(applied[0], applied[1], applied[2]).mul(scale);
            var jetTorque = new Vector3d(applied[3], applied[4], applied[5]).mul(scale / TORQUE_SCALE);
            var wheel = limit(torque.sub(jetTorque, new Vector3d()), wheelLimit);
            return new Result(openings, wheel, actualForce, jetTorque.add(wheel), fuel);
        }

        private void solveNormal(double[] result) {
            for (int row = 0; row < DIMENSIONS; row++) {
                for (int col = 0; col < row; col++) {
                    result[row] -= lower[row][col] * result[col];
                }
                result[row] /= lower[row][row];
            }
            for (int row = DIMENSIONS - 1; row >= 0; row--) {
                for (int col = row + 1; col < DIMENSIONS; col++) {
                    result[row] -= lower[col][row] * result[col];
                }
                result[row] /= lower[row][row];
            }
        }
    }

    private static boolean matches(double[] actual, double[] target) {
        for (int axis = 0; axis < DIMENSIONS; axis++) {
            if (Math.abs(actual[axis] - target[axis]) > TOLERANCE / 16) {
                return false;
            }
        }
        return true;
    }

    private static double updateActuator(int i, double next, double[] free, double[] bounded, double[] dual) {
        double residual = free[i] - next;
        dual[i] += residual;
        double error = Math.max(Math.abs(residual), Math.abs(next - bounded[i]));
        bounded[i] = next;
        return error;
    }

    private static void setWrench(double[] out, Vector3dc force, Vector3dc torque) {
        for (int axis = 0; axis < 3; axis++) {
            out[axis] = force.get(axis);
            out[3 + axis] = torque.get(axis);
        }
    }

    private static void addScaled(double[] out, double[] column, double scale) {
        for (int axis = 0; axis < DIMENSIONS; axis++) {
            out[axis] += column[axis] * scale;
        }
    }

    private static double dot(double[] column, double[] values) {
        double sum = 0;
        for (int axis = 0; axis < DIMENSIONS; axis++) {
            sum += column[axis] * values[axis];
        }
        return sum;
    }
}
