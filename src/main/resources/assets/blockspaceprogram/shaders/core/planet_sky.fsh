#version 150

uniform mat4 ClipToWorld;
uniform int Mode;
uniform vec3 BodyDirection;
uniform float Radius;
uniform vec3 LightDirection;
uniform vec3 BodyPole;
uniform vec3 PrimeMeridian;
uniform float Density;
uniform float Weather;
uniform float Horizon;

in vec2 screenPosition;
out vec4 fragColor;

vec3 atmosphere(vec3 ray) {
    float daylight = smoothstep(-0.18, 0.15, LightDirection.y);
    float horizonGlow = exp(-5.0 * abs(ray.y));
    vec3 day = mix(vec3(0.26, 0.50, 0.86), vec3(0.68, 0.78, 0.88), horizonGlow);
    day = mix(day, vec3(0.37, 0.42, 0.46), Weather * 0.75);
    float twilight = max(0.0, 1.0 - abs(LightDirection.y) * 5.0);
    float towardSun = pow(max(0.0, dot(ray, LightDirection)), 4.0);
    vec3 glow = vec3(0.72, 0.25, 0.08) * twilight * towardSun * horizonGlow * (1.0 - Weather);
    return Density * (day * daylight + glow);
}

vec3 surfaceColor(vec3 normal) {
    float relief = sin(6.0 * normal.x + sin(8.0 * normal.z)) * sin(7.0 * normal.z - 4.0 * normal.y);
    relief += 0.34 * sin(19.0 * normal.x - 17.0 * normal.y) * cos(23.0 * normal.z);
    relief += 0.12 * cos(47.0 * normal.y + 41.0 * normal.z) * sin(43.0 * normal.x);
    if (Mode == 1) {
        return mix(vec3(0.30, 0.29, 0.28), vec3(0.68, 0.65, 0.60), smoothstep(-0.5, 0.7, relief));
    }
    vec3 water = mix(vec3(0.025, 0.13, 0.31), vec3(0.05, 0.30, 0.45), smoothstep(-0.5, 0.08, relief));
    vec3 land = mix(vec3(0.22, 0.40, 0.18), vec3(0.53, 0.43, 0.27), smoothstep(0.18, 0.9, relief));
    vec3 ground = mix(water, land, smoothstep(0.08, 0.16, relief));
    return mix(ground, vec3(0.79, 0.85, 0.87), smoothstep(0.83, 0.94, abs(normal.y) + 0.06 * relief));
}

void main() {
    vec3 ray = normalize((ClipToWorld * vec4(screenPosition, 1.0, 1.0)).xyz);
    if (Mode == 0) {
        fragColor = vec4(atmosphere(ray), 1.0);
        return;
    }

    // Intersect the view ray with a sphere at unit distance, with radius R / distance.
    float alongRay = dot(ray, BodyDirection);
    vec3 perpendicular = cross(ray, BodyDirection);
    float discriminant = Radius * Radius - dot(perpendicular, perpendicular);
    if (alongRay <= 0.0 || discriminant < 0.0 || ray.y < Horizon) {
        discard;
    }
    vec3 point = ray * (alongRay - sqrt(discriminant));
    vec3 normal = normalize(point - BodyDirection);
    vec3 color;
    if (Mode == 3) {
        color = mix(vec3(1.0, 0.70, 0.28), vec3(1.0, 0.97, 0.79), max(0.0, dot(-ray, normal)));
    } else {
        float light = max(0.0, dot(normal, LightDirection));
        vec3 fixedNormal = vec3(dot(normal, PrimeMeridian), dot(normal, BodyPole),
                                dot(normal, cross(PrimeMeridian, BodyPole)));
        color = surfaceColor(fixedNormal) * (0.035 + 0.965 * pow(light, 0.65));
    }
    fragColor = vec4(mix(color, atmosphere(ray), Density * (0.35 + 0.65 * Weather)), 1.0);
}
