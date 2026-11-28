uniform sampler2D SurfaceMap;
uniform float SurfaceMapBlend;

vec4 mappedSurface(vec3 normal, float footprint) {
    // The third component of fixedNormal points west. Grid rows run north to south.
    float longitude = atan(-normal.z, normal.x);
    float latitude = asin(clamp(normal.y, -1.0, 1.0));
    vec2 uv = vec2(0.5 + longitude / TWO_PI, 0.5 - 2.0 * latitude / TWO_PI);
    // Longitude cells narrow toward the poles. Explicit LOD also avoids a derivative jump at the seam.
    float width = float(textureSize(SurfaceMap, 0).x);
    float latitudeScale = max(length(normal.xz), 2.0 / width);
    float texels = (width / TWO_PI) * footprint / latitudeScale;
    return textureLod(SurfaceMap, uv, log2(max(1.0, texels)));
}
