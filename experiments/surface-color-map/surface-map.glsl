uniform sampler2D SurfaceMap;
uniform int HasSurfaceMap;

vec3 mappedSurfaceColor(vec3 normal) {
    // The third component of fixedNormal points west. Grid rows run north to south.
    float longitude = atan(-normal.z, normal.x);
    float latitude = asin(clamp(normal.y, -1.0, 1.0));
    vec2 uv = vec2(0.5 + longitude / 6.28318530718, 0.5 - latitude / 3.14159265359);
    return texture(SurfaceMap, uv).rgb;
}
