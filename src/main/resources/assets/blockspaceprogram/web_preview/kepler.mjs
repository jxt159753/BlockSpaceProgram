const TAU = 2 * Math.PI;

export function periapsis(orbit) {
    return orbit.semiLatusRectum / (1 + orbit.eccentricity);
}

export function apoapsis(orbit) {
    return orbit.eccentricity < 1 ? orbit.semiLatusRectum / (1 - orbit.eccentricity) : Infinity;
}

export function period(orbit, mu) {
    return orbit.eccentricity < 1 ? TAU / meanMotion(mu, periapsis(orbit) / (1 - orbit.eccentricity)) : Infinity;
}

// The same perifocal axes as OrbitElements.stateAt and the game's inertial map.
function rotate(orbit, x, y) {
    const cosNode = Math.cos(orbit.longitudeOfAscendingNode);
    const sinNode = Math.sin(orbit.longitudeOfAscendingNode);
    const cosW = Math.cos(orbit.argumentOfPeriapsis);
    const sinW = Math.sin(orbit.argumentOfPeriapsis);
    const along = x * cosW - y * sinW;
    const across = x * sinW + y * cosW;
    const planar = across * Math.cos(orbit.inclination);
    return [cosNode * along - sinNode * planar, sinNode * along + cosNode * planar, across * Math.sin(orbit.inclination)];
}

export function pointOnOrbit(orbit, anomaly) {
    const radius = orbit.semiLatusRectum / (1 + orbit.eccentricity * Math.cos(anomaly));
    return rotate(orbit, radius * Math.cos(anomaly), radius * Math.sin(anomaly));
}

export function positionAt(orbit, mu, seconds) {
    const elapsed = seconds - orbit.epochSeconds;
    const e = orbit.eccentricity;
    const p = orbit.semiLatusRectum;
    const q = periapsis(orbit);
    if (e === 1) {
        const mean = orbit.meanAnomalyAtEpoch + 2 * meanMotion(mu, p) * elapsed;
        const d = 2 * Math.sinh(Math.asinh(1.5 * mean) / 3);
        return rotate(orbit, q * (1 - d * d), 2 * q * d);
    }
    const a = q / Math.abs(1 - e);
    const hyperbolic = e > 1;
    let mean = orbit.meanAnomalyAtEpoch + meanMotion(mu, a) * elapsed;
    if (!hyperbolic) {
        mean %= TAU;
        if (mean > Math.PI) mean -= TAU;
        if (mean < -Math.PI) mean += TAU;
    }
    const anomaly = solveAnomaly(mean, e, hyperbolic);
    const half = hyperbolic ? Math.sinh(anomaly / 2) : Math.sin(anomaly / 2);
    const sine = hyperbolic ? Math.sinh(anomaly) : Math.sin(anomaly);
    return rotate(orbit, q - 2 * a * half * half, Math.sqrt(a) * Math.sqrt(p) * sine);
}

function meanMotion(mu, length) {
    return Math.sqrt(mu / length) / length;
}

function solveAnomaly(mean, e, hyperbolic) {
    if (mean === 0 || e === 0) return mean;
    const target = Math.abs(mean);
    let low = 0;
    let high = hyperbolic ? Math.asinh(target / e) + 1 : Math.PI;
    let anomaly = hyperbolic ? Math.asinh(target / e) : target;
    while (kepler(high, e, hyperbolic) < target) high *= 2;
    for (let step = 0; step < 64; step++) {
        const error = kepler(anomaly, e, hyperbolic) - target;
        const half = hyperbolic ? Math.sinh(anomaly / 2) : Math.sin(anomaly / 2);
        const correction = error / (Math.abs(1 - e) + 2 * e * half * half);
        if (Math.abs(correction) <= 1e-13) break;
        if (error > 0) {
            high = anomaly;
        } else {
            low = anomaly;
        }
        const next = anomaly - correction;
        anomaly = next > low && next < high ? next : (low + high) / 2;
    }
    return Math.sign(mean) * anomaly;
}

function kepler(anomaly, e, hyperbolic) {
    if (Math.abs(anomaly) < 0.1) {
        // Preserve precision near the nearly parabolic periapsis.
        const square = anomaly * anomaly;
        const signed = hyperbolic ? square : -square;
        const remainder = anomaly * square / 6 * (1 + signed / 20 * (1 + signed / 42 * (1 + signed / 72)));
        return Math.abs(1 - e) * anomaly + e * remainder;
    }
    return hyperbolic ? e * Math.sinh(anomaly) - anomaly : anomaly - e * Math.sin(anomaly);
}
