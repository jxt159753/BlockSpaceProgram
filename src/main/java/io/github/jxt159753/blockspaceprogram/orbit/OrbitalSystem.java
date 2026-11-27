package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class OrbitalSystem {
    public static final ResourceLocation MOTHER_PLANET = ResourceLocation.withDefaultNamespace("overworld");
    public static final ResourceLocation MOON = ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "moon");
    public static final OrbitalSystem M3 = new OrbitalSystem(
            new CelestialBody(MOTHER_PLANET, 60_000, 3.528e10, null),
            new CelestialBody(MOON, 12_000, 2.304e8,
                    new Orbit(MOTHER_PLANET, OrbitElements.fromSemiMajorAxis(360_000, 0, 0, 0, 0, 0, 0))));

    private static final OrbitalState ORIGIN = new OrbitalState(Vec3.ZERO, Vec3.ZERO);
    private static final double CROSSING_TIME_TOLERANCE = 1e-3;
    // Keep rounding at the shared boundary from immediately undoing a patch.
    private static final double SOI_MARGIN = 1e-3;

    private final Map<ResourceLocation, CelestialBody> bodies = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<CelestialBody>> children = new LinkedHashMap<>();
    private final Map<ResourceLocation, Double> sphereRadii = new LinkedHashMap<>();

    /** Supply satellites with each parent before its children. */
    public OrbitalSystem(CelestialBody root, CelestialBody... satellites) {
        if (root.orbit() != null) {
            throw new IllegalArgumentException("The root body cannot orbit another body");
        }
        bodies.put(root.id(), root);
        sphereRadii.put(root.id(), Double.POSITIVE_INFINITY);
        for (CelestialBody satellite : satellites) {
            Orbit orbit = satellite.orbit();
            if (orbit == null || orbit.elements().eccentricity() >= 1 || bodies.containsKey(satellite.id())) {
                throw new IllegalArgumentException("Expected a unique satellite with a bound orbit: " + satellite.id());
            }
            CelestialBody parent = body(orbit.center());
            double radius = orbit.elements().semiMajorAxis()
                    * Math.pow(satellite.gravitationalParameter() / parent.gravitationalParameter(), 0.4);
            if (radius <= satellite.radius() || orbit.elements().periapsis() - radius <= parent.radius()
                    || orbit.elements().apoapsis() + radius >= sphereOfInfluence(parent.id())) {
                throw new IllegalArgumentException("Sphere of influence does not fit around " + satellite.id());
            }
            bodies.put(satellite.id(), satellite);
            sphereRadii.put(satellite.id(), radius);
            children.computeIfAbsent(parent.id(), id -> new ArrayList<>()).add(satellite);
        }
    }

    public Collection<CelestialBody> bodies() {
        return Collections.unmodifiableCollection(bodies.values());
    }

    public CelestialBody body(ResourceLocation id) {
        CelestialBody body = bodies.get(id);
        if (body == null) {
            throw new IllegalArgumentException("Unknown celestial body: " + id);
        }
        return body;
    }

    public double sphereOfInfluence(ResourceLocation id) {
        return sphereRadii.get(body(id).id());
    }

    public OrbitalState relativeState(Orbit orbit, double seconds) {
        return orbit.elements().stateAt(body(orbit.center()).gravitationalParameter(), seconds);
    }

    public OrbitalState bodyState(ResourceLocation id, double seconds) {
        OrbitalState state = ORIGIN;
        Orbit orbit = body(id).orbit();
        while (orbit != null) {
            state = state.add(relativeState(orbit, seconds));
            orbit = body(orbit.center()).orbit();
        }
        return state;
    }

    public OrbitalState absoluteState(Orbit orbit, double seconds) {
        return bodyState(orbit.center(), seconds).add(relativeState(orbit, seconds));
    }

    /** Apply each SOI crossing in order and return the conic valid at the end of the interval. */
    public Orbit advance(Orbit orbit, double fromSeconds, double toSeconds) {
        return advance(orbit, fromSeconds, toSeconds, ignored -> {});
    }

    /** Compute SOI patches once and evaluate the resulting inertial path within the supplied interval. */
    public DoubleFunction<OrbitalState> forecast(Orbit orbit, double fromSeconds, double toSeconds) {
        List<Orbit> legs = new ArrayList<>();
        legs.add(orbit);
        advance(orbit, fromSeconds, toSeconds, legs::add);
        return seconds -> {
            for (int i = legs.size() - 1; i > 0; i--) {
                Orbit leg = legs.get(i);
                if (seconds >= leg.elements().epochSeconds()) {
                    return absoluteState(leg, seconds);
                }
            }
            return absoluteState(orbit, seconds);
        };
    }

    private Orbit advance(Orbit orbit, double fromSeconds, double toSeconds, Consumer<Orbit> onCrossing) {
        if (!Double.isFinite(fromSeconds) || !Double.isFinite(toSeconds) || fromSeconds > toSeconds) {
            throw new IllegalArgumentException("Expected an ordered finite propagation interval");
        }
        Crossing crossing;
        while ((crossing = nextCrossing(orbit, fromSeconds, toSeconds)) != null) {
            double epoch = crossing.seconds();
            CelestialBody nextCenter = body(crossing.center());
            OrbitalState relative = absoluteState(orbit, epoch).subtract(bodyState(nextCenter.id(), epoch));
            orbit = new Orbit(nextCenter.id(), OrbitElements.fromState(nextCenter.gravitationalParameter(), relative, epoch));
            onCrossing.accept(orbit);
            fromSeconds = epoch;
        }
        return orbit;
    }

    boolean mayCross(Orbit orbit) {
        if (canExit(orbit)) {
            return true;
        }
        for (CelestialBody child : children.getOrDefault(orbit.center(), List.of())) {
            if (canEnter(orbit.elements(), child)) {
                return true;
            }
        }
        return false;
    }

    private boolean canExit(Orbit orbit) {
        return orbit.elements().apoapsis() > sphereOfInfluence(orbit.center()) + SOI_MARGIN;
    }

    private boolean canEnter(OrbitElements elements, CelestialBody child) {
        OrbitElements childElements = child.orbit().elements();
        double radius = sphereOfInfluence(child.id()) - SOI_MARGIN;
        return elements.apoapsis() > childElements.periapsis() - radius
                && elements.periapsis() < childElements.apoapsis() + radius;
    }

    @Nullable
    private Crossing nextCrossing(Orbit orbit, double from, double to) {
        CelestialBody center = body(orbit.center());
        double mu = center.gravitationalParameter();
        double shipSpeed = orbit.elements().maxSpeed(mu);
        Crossing next = null;
        if (canExit(orbit)) {
            double radius = sphereOfInfluence(center.id()) + SOI_MARGIN;
            double time = crossingTime(t -> radius - relativeState(orbit, t).position().length(), shipSpeed, from, to);
            if (Double.isFinite(time)) {
                next = new Crossing(center.orbit().center(), time);
                to = time;
            }
        }
        for (CelestialBody child : children.getOrDefault(center.id(), List.of())) {
            if (canEnter(orbit.elements(), child)) {
                Orbit childOrbit = child.orbit();
                double radius = sphereOfInfluence(child.id()) - SOI_MARGIN;
                double relativeSpeed = shipSpeed + childOrbit.elements().maxSpeed(mu);
                double time = crossingTime(t -> relativeState(orbit, t).position()
                        .distanceTo(relativeState(childOrbit, t).position()) - radius, relativeSpeed, from, to);
                if (Double.isFinite(time) && (next == null || time < next.seconds())) {
                    next = new Crossing(child.id(), time);
                    to = time;
                }
            }
        }
        return next;
    }

    private static double crossingTime(DoubleUnaryOperator clearance, double speed, double from, double to) {
        return crossingTime(clearance, speed, from, clearance.applyAsDouble(from), to, clearance.applyAsDouble(to));
    }

    private static double crossingTime(DoubleUnaryOperator clearance, double speed,
                                       double from, double start, double to, double end) {
        if (start < 0) {
            return from;
        }
        // The sum bounds whether a crossing and return could fit between two same-side samples.
        double elapsed = to - from;
        if (start + end > speed * elapsed) {
            return Double.POSITIVE_INFINITY;
        }
        double middle = from + elapsed / 2;
        if (elapsed <= CROSSING_TIME_TOLERANCE || middle == from || middle == to) {
            return end < 0 ? to : Double.POSITIVE_INFINITY;
        }
        double mid = clearance.applyAsDouble(middle);
        double first = crossingTime(clearance, speed, from, start, middle, mid);
        return Double.isFinite(first) ? first : crossingTime(clearance, speed, middle, mid, to, end);
    }

    private record Crossing(ResourceLocation center, double seconds) {}
}
