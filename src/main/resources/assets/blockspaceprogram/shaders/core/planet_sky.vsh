#version 150

uniform mat4 ClipToWorld;
uniform int Mode;
uniform vec3 BodyDirection;
uniform float Radius;
uniform float Density;
uniform float StarBrightness;
uniform float HaloRadius;
uniform vec2 PixelPadding;

in vec3 Position;
out vec2 screenPosition;

vec2 discPosition(vec2 corner) {
    float radius = Radius;
    if (Mode == 1 && Density > 0.0 && StarBrightness > 0.0) {
        radius *= HaloRadius;
    }
    // Invert the fragment shader's mapping from (screen x, screen y, 1) to a view ray.
    mat3 screenToRay = mat3(ClipToWorld[0].xyz, ClipToWorld[1].xyz, (ClipToWorld[2] + ClipToWorld[3]).xyz);
    mat3 rayToScreen = inverse(screenToRay);
    mat3 rows = transpose(rayToScreen);
    vec3 center = rayToScreen * BodyDirection;
    vec3 extent = radius * vec3(length(rows[0]), length(rows[1]), length(rows[2]));
    vec3 lower = center - extent;
    vec3 upper = center + extent;
    if (upper.z <= 0.0) {
        return vec2(2.0);
    }
    if (lower.z <= 0.0) {
        return corner;
    }
    // Bound the perspective divide and leave two pixels for limb coverage.
    vec2 minCorner = min(lower.xy / lower.z, lower.xy / upper.z) - PixelPadding;
    vec2 maxCorner = max(upper.xy / lower.z, upper.xy / upper.z) + PixelPadding;
    return mix(clamp(minCorner, -1.0, 1.0), clamp(maxCorner, -1.0, 1.0), 0.5 * (corner + 1.0));
}

void main() {
    screenPosition = Mode == 0 ? Position.xy : discPosition(Position.xy);
    gl_Position = vec4(screenPosition, Position.z, 1.0);
}
