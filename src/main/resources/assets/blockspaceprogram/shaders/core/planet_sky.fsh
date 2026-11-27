#version 150

uniform mat4 ClipToWorld;
uniform int Mode;
uniform vec3 BodyDirection;
uniform float Radius;
uniform vec3 LightDirection;
uniform vec3 BodyPole;
uniform vec3 PrimeMeridian;
uniform int SurfaceSeed;
uniform float Density;
uniform float SkyDensity;
uniform float Weather;
uniform float StarBrightness;
uniform float HaloRadius;
uniform float SolarHaloRadius;
uniform float Horizon;
// Outer radius and scale height, divided by distance to the body, followed by density at the top.
uniform vec3 BodyAtmosphere;
uniform vec2 CloudRotation;

in vec2 screenPosition;
out vec4 fragColor;

const vec3 AIR_EXTINCTION = vec3(0.047, 0.085, 0.145);
const vec3 LIMB_DAY = vec3(0.15, 0.46, 0.86);
const vec3 LIMB_TWILIGHT = vec3(0.86, 0.34, 0.11);
const vec3 CLOUD_COLOR = vec3(0.92, 0.94, 0.97);
const vec2 CLOUD_THRESHOLDS = vec2(0.47, 0.66);
const vec3 WATER_GLINT = vec3(1.0, 0.91, 0.75);
const vec3 SOLAR_HALO = vec3(1.0, 0.77, 0.49);
const vec2 SOLAR_HALO_STRENGTH = vec2(0.44, 0.11);

vec3 atmosphere(vec3 ray) {
    float daylight = smoothstep(-0.18, 0.15, LightDirection.y);
    float horizonGlow = exp(-5.0 * abs(ray.y));
    vec3 day = mix(vec3(0.26, 0.50, 0.86), vec3(0.68, 0.78, 0.88), horizonGlow);
    day = mix(day, vec3(0.37, 0.42, 0.46), Weather * 0.75);
    float twilight = max(0.0, 1.0 - abs(LightDirection.y) * 5.0);
    float towardSun = pow(max(0.0, dot(ray, LightDirection)), 4.0);
    vec3 glow = vec3(0.72, 0.25, 0.08) * twilight * towardSun * horizonGlow * (1.0 - Weather);
    return SkyDensity * (day * daylight + glow);
}

vec3 transmittance(vec3 ray) {
    if (Density <= 0.0) {
        return vec3(1.0);
    }
    // A curved atmosphere has a finite path length even along the horizon.
    float zenithCosine = max(0.0, ray.y);
    float airMass = inversesqrt(0.009 + 0.991 * zenithCosine * zenithCosine);
    vec3 extinction = AIR_EXTINCTION + 1.45 * Weather;
    return exp(-Density * airMass * extinction);
}

struct Air {
    vec3 scatter;
    vec3 transmission;
};

Air traceAtmosphere(vec3 ray, float alongRay, float shellDiscriminant, float surfaceDistance, float coverage) {
    float shellDepth = sqrt(max(0.0, shellDiscriminant));
    float near = max(0.0, alongRay - shellDepth);
    // Rays reaching the surface traverse only the near side of the atmosphere.
    float far = mix(alongRay + shellDepth, surfaceDistance, coverage);
    if (far <= near || shellDiscriminant <= 0.0) {
        return Air(vec3(0.0), vec3(1.0));
    }

    float closest = clamp(alongRay, near, far);
    vec3 position = closest * ray - BodyDirection;
    float radius = length(position);
    float scaleHeight = BodyAtmosphere.y;
    float topDensity = BodyAtmosphere.z;
    float density = exp(-max(0.0, radius - Radius) / scaleHeight);
    density = max(0.0, density - topDensity) / max(1.0 - topDensity, 1e-6);

    // Approximate the column by the exponential at the densest point and the ray
    // distance to a rise of one scale height. The scale stays finite at tangency.
    float radialOffset = abs(closest - alongRay);
    float rise = scaleHeight * (2.0 * radius + scaleHeight);
    float falloffLength = rise / (sqrt(radialOffset * radialOffset + rise) + radialOffset);
    vec2 span = vec2(closest - near, far - closest) / falloffLength;
    float column = density * (falloffLength / scaleHeight) * (2.0 - exp(-span.x) - exp(-span.y));
    vec3 transmission = exp(-column * AIR_EXTINCTION);

    float sun = dot(position, LightDirection) / radius;
    float illumination = smoothstep(-0.012, 0.12, sun);
    vec3 tint = mix(LIMB_TWILIGHT, LIMB_DAY, smoothstep(0.12, 0.40, sun));
    return Air(tint * (illumination * (1.0 - transmission.b)), transmission);
}

const float TWO_PI = 6.28318530718;

// Basin centers and chord radii. Overlapping basins form the larger maria.
const vec4 MARIA[9] = vec4[9](
    vec4(0.8803, 0.3201, -0.3501, 0.370),
    vec4(0.7196, 0.5777, -0.3852, 0.245),
    vec4(0.9580, 0.0916, -0.2718, 0.183),
    vec4(0.8360, -0.3727, 0.4029, 0.385),
    vec4(0.6678, -0.2592, 0.6977, 0.240),
    vec4(0.6124, 0.6024, 0.5120, 0.167),
    vec4(-0.7320, 0.4613, 0.5014, 0.316),
    vec4(-0.8508, -0.4004, 0.3403, 0.274),
    vec4(-0.7492, 0.2597, -0.6093, 0.208)
);
const vec4 CRATERS[6] = vec4[6](
    vec4(0.9199, -0.3700, -0.1300, 0.087),
    vec4(0.8190, 0.2996, 0.4894, 0.067),
    vec4(0.5500, 0.8000, -0.2400, 0.076),
    vec4(-0.7887, -0.3294, -0.5191, 0.108),
    vec4(-0.4209, 0.6313, 0.6514, 0.088),
    vec4(-0.6721, 0.6721, -0.3109, 0.076)
);

vec4 randomUnit(uvec4 bits) {
    bits = (bits ^ (bits >> 16u)) * 0x85ebca6bu;
    bits = (bits ^ (bits >> 13u)) * 0xc2b2ae35u;
    return vec4((bits ^ (bits >> 16u)) >> 8u) * (1.0 / 16777216.0);
}

float surfaceNoise(vec3 point) {
    vec3 cell = floor(point);
    vec3 t = point - cell;
    t = t * t * (3.0 - 2.0 * t);
    const uint X = 0x9e3779b1u;
    const uint Y = 0x85ebca77u;
    const uint Z = 0xc2b2ae3du;
    uvec3 index = uvec3(ivec3(cell));
    uint key = uint(SurfaceSeed) + index.x * X + index.y * Y + index.z * Z;
    uvec4 corners = key + uvec4(0u, X, Y, X + Y);
    vec4 values = mix(randomUnit(corners), randomUnit(corners + Z), t.z);
    return mix(mix(values.x, values.y, t.x), mix(values.z, values.w, t.x), t.y);
}

float filteredSurfaceNoise(vec3 point, float frequency, float footprint) {
    float detail = 1.0 - smoothstep(0.30, 0.90, footprint * frequency);
    return detail > 0.0 ? (surfaceNoise(point * frequency) - 0.5) * detail : 0.0;
}

float surfaceDetail(vec3 normal, float footprint) {
    return 0.48 * filteredSurfaceNoise(normal, 250.0, footprint)
         + 0.92 * filteredSurfaceNoise(normal, 1500.0, footprint);
}

float craterRelief(vec3 normal, vec4 crater, float footprint, uint craterIndex) {
    vec3 offset = normal - crater.xyz;
    float distanceSquared = dot(offset, offset);
    float extent = 3.3 * crater.w;
    if (distanceSquared >= extent * extent) {
        return 0.0;
    }
    float distance = sqrt(distanceSquared);
    float radial = distance / crater.w;
    float detail = 1.0 - smoothstep(0.25, 0.90, footprint / crater.w);
    float floorShade = 1.0 - smoothstep(0.50, 0.96, radial);
    float rim = smoothstep(0.62, 0.94, radial) * (1.0 - smoothstep(0.94, 1.31, radial));
    float rays = 0.0;
    float rayDetail = 1.0 - smoothstep(0.16, 0.46, footprint / max(distance, crater.w));
    if (radial > 1.0 && rayDetail > 0.0) {
        vec3 tangent = normalize(cross(crater.xyz, vec3(0.0, 0.0, 1.0)));
        vec3 bitangent = cross(crater.xyz, tangent);
        float sector = 14.0 * (atan(dot(offset, bitangent), dot(offset, tangent)) / TWO_PI + 0.5);
        uint key = uint(SurfaceSeed) ^ (craterIndex * 0x9e3779b1u + uint(sector) % 14u);
        float jitter = randomUnit(uvec4(key)).x;
        float streak = 1.0 - smoothstep(0.03, 0.27, abs(fract(sector) - mix(0.30, 0.70, jitter)));
        float lengthFade = 1.0 - smoothstep(1.25, mix(1.7, 3.3, jitter), radial);
        rays = streak * lengthFade * smoothstep(1.0, 1.18, radial) * rayDetail;
    }
    return detail * (0.17 * rim - 0.18 * floorShade) + 0.18 * rays;
}

vec3 lunarSurface(vec3 normal, float footprint) {
    // Rotate the basins and craters around the body-fixed prime meridian.
    float angle = float(uint(SurfaceSeed) & 65535u) * (TWO_PI / 65536.0);
    float c = cos(angle);
    float s = sin(angle);
    normal.yz = mat2(c, s, -s, c) * normal.yz;

    float shore = -1.0;
    for (int i = 0; i < MARIA.length(); i++) {
        vec4 basin = MARIA[i];
        float distanceSquared = 2.0 * (1.0 - dot(normal, basin.xyz));
        shore = max(shore, basin.w * basin.w - distanceSquared);
    }
    float terrain = filteredSurfaceNoise(normal, 6.0, footprint);
    float shoreWidth = max(0.025, 0.48 * footprint);
    float mare = smoothstep(-shoreWidth, shoreWidth, shore + 0.25 * terrain);
    float relief = 0.22 * terrain + 0.17 * filteredSurfaceNoise(normal, 24.0, footprint)
                 + 0.23 * surfaceDetail(normal, footprint);
    for (int i = 0; i < CRATERS.length(); i++) {
        relief += craterRelief(normal, CRATERS[i], footprint, uint(i));
    }
    return mix(vec3(0.97, 0.96, 0.944), vec3(0.56, 0.546, 0.524), mare) + relief;
}

float cloudOpacity(vec3 point, float footprint) {
    point.xz = mat2(CloudRotation.x, CloudRotation.y, -CloudRotation.y, CloudRotation.x) * point.xz;
    point.y *= 1.3;
    point += vec3(2.1, 0.5, 1.7);
    float pattern = 0.5 + filteredSurfaceNoise(point, 8.0, footprint)
                  + 0.52 * filteredSurfaceNoise(point, 21.0, footprint)
                  + 0.28 * filteredSurfaceNoise(point, 52.0, footprint)
                  + 0.15 * filteredSurfaceNoise(point, 185.0, footprint);
    return 0.90 * smoothstep(CLOUD_THRESHOLDS.x, CLOUD_THRESHOLDS.y, pattern);
}

vec3 atmosphericSurface(vec3 normal, vec3 ray, vec3 worldNormal, float light, float footprint) {
    float relief = sin(6.0 * normal.x + sin(8.0 * normal.z)) * sin(7.0 * normal.z - 4.0 * normal.y);
    relief += 0.34 * sin(19.0 * normal.x - 17.0 * normal.y) * cos(23.0 * normal.z);
    relief += 0.12 * cos(47.0 * normal.y + 41.0 * normal.z) * sin(43.0 * normal.x);
    vec3 water = mix(vec3(0.025, 0.13, 0.31), vec3(0.05, 0.30, 0.45), smoothstep(-0.5, 0.08, relief));
    vec3 land = mix(vec3(0.22, 0.40, 0.18), vec3(0.53, 0.43, 0.27), smoothstep(0.18, 0.9, relief));
    float landFraction = smoothstep(0.08, 0.16, relief);
    float ice = smoothstep(0.83, 0.94, abs(normal.y) + 0.06 * relief);
    vec3 ground = mix(mix(water, land, landFraction), vec3(0.79, 0.85, 0.87), ice);
    ground *= 1.0 + surfaceDetail(normal, footprint);

    float clouds = cloudOpacity(normal, footprint);
    float daylight = pow(light, 0.65);
    vec3 color = mix(ground * (0.035 + 0.965 * daylight), CLOUD_COLOR * daylight, clouds);
    float waterFraction = (1.0 - landFraction) * (1.0 - ice);
    float reflection = max(0.0, dot(reflect(-LightDirection, worldNormal), -ray));
    float glint = pow(reflection, 84.0) * smoothstep(0.0, 0.08, light);
    return color + WATER_GLINT * (glint * waterFraction * (1.0 - clouds));
}

void main() {
    if (Mode == 0 && SkyDensity <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 ray = normalize((ClipToWorld * vec4(screenPosition, 1.0, 1.0)).xyz);
    if (Mode == 0) {
        fragColor = vec4(atmosphere(ray), 1.0);
        return;
    }

    // Intersect the view ray with a sphere at unit distance, with radius R / distance.
    float alongRay = dot(ray, BodyDirection);
    vec3 perpendicular = cross(ray, BodyDirection);
    float distanceSquared = dot(perpendicular, perpendicular);
    float radiusSquared = Radius * Radius;
    float discriminant = radiusSquared - distanceSquared;
    float pixelWidth = max(fwidth(discriminant), 1e-10);
    float coverage = alongRay > 0.0 ? clamp(0.5 + discriminant / pixelWidth, 0.0, 1.0) : 0.0;
    bool atmospheric = Mode == 2 || Mode == 4;
    // Evaluate ray derivatives before the non-uniform disk and horizon tests.
    float rayStep = Mode == 3 ? 0.0 : max(length(dFdx(ray)), length(dFdy(ray)));
    bool lunarHalo = Mode == 1 && Density > 0.0 && StarBrightness > 0.0;
    bool solarHalo = Mode == 3 && Density < 1.0;
    float haloRadius = solarHalo ? SolarHaloRadius : HaloRadius;
    bool inHalo = alongRay > 0.0 && (lunarHalo || solarHalo)
               && distanceSquared < radiusSquared * haloRadius * haloRadius;
    float outerRadius = BodyAtmosphere.x;
    float shellDiscriminant = outerRadius * outerRadius - distanceSquared;
    bool inShell = atmospheric && outerRadius > Radius && shellDiscriminant > 0.0
                && (alongRay > 0.0 || outerRadius > 1.0);
    if (ray.y < Horizon || (coverage == 0.0 && !inHalo && !inShell)) {
        discard;
    }

    vec3 transmission = Mode == 4 ? vec3(1.0) : transmittance(ray);
    float litFraction = 0.5 * (1.0 - dot(BodyDirection, LightDirection));
    vec3 halo = vec3(0.0);
    if (inHalo) {
        float radial = sqrt(distanceSquared) / Radius;
        if (lunarHalo) {
            float falloff = 1.0 - smoothstep(1.0, HaloRadius, radial);
            halo = transmission * (0.058 * Density * StarBrightness * litFraction * falloff);
        } else if (coverage < 1.0) {
            float fromEdge = max(0.0, radial - 1.0);
            float falloff = dot(SOLAR_HALO_STRENGTH, exp(-fromEdge * vec2(1.4, 0.42)));
            falloff *= 1.0 - smoothstep(6.0, SolarHaloRadius, radial);
            float vacuum = 1.0 - Density;
            halo = transmission * SOLAR_HALO * (falloff * vacuum * vacuum * (1.0 - coverage));
        }
    }

    float depth = sqrt(max(0.0, discriminant));
    float distance = alongRay - depth;
    vec3 color = vec3(0.0);
    if (coverage > 0.0) {
        vec3 normal = normalize(ray * distance - BodyDirection);
        if (Mode == 3) {
            color = mix(vec3(1.0, 0.70, 0.28), vec3(1.0, 0.97, 0.79), max(0.0, dot(-ray, normal)));
            color *= 1.0 + Density;
        } else {
            float light = max(0.0, dot(normal, LightDirection));
            vec3 fixedNormal = vec3(dot(normal, PrimeMeridian), dot(normal, BodyPole),
                                    dot(normal, cross(PrimeMeridian, BodyPole)));
            float viewCosine = depth / Radius;
            // A pixel spans more of the surface near the limb. The square-root bound
            // accounts for curvature at tangency, where the linear estimate diverges.
            float projectedStep = rayStep * distance / Radius;
            float footprint = projectedStep / max(viewCosine, sqrt(projectedStep));
            if (Mode == 1) {
                // Lommel-Seeliger, normalized to a uniform disk at full phase.
                float reflected = 2.0 * light / max(light + viewCosine, 1e-4);
                // Limit the grazing-angle gain to keep albedo detail visible from close orbit.
                reflected = min(reflected, 1.05) * mix(0.49, 1.0, litFraction);
                float earthshine = 0.10 * (1.0 - litFraction) * StarBrightness;
                color = lunarSurface(fixedNormal, footprint) * (reflected + earthshine);
            } else {
                color = atmosphericSurface(fixedNormal, ray, normal, light, footprint);
            }
        }
    }

    vec3 sky = Mode == 4 || SkyDensity <= 0.0 ? vec3(0.0) : atmosphere(ray);
    if (inShell) {
        Air air = traceAtmosphere(ray, alongRay, shellDiscriminant, distance, coverage);
        float opacity = 1.0 - (1.0 - coverage) * air.transmission.b;
        fragColor = vec4(opacity * sky + transmission * (air.scatter + coverage * air.transmission * color),
                         opacity);
        return;
    }
    vec3 disk = sky + transmission * color;
    if (Mode == 3) {
        // Expose the solar disk without clipping away the sunset tint.
        disk /= max(1.0, max(disk.r, max(disk.g, disk.b)));
    }
    fragColor = vec4(coverage * disk + halo, coverage);
}
