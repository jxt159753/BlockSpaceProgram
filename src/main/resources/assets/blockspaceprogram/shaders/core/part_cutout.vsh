#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 PartPose;
uniform mat3 PartNormal;
uniform ivec2 PartLight;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
uniform int FogShape;

out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out float vertexDistance;

void main() {
    vec4 cameraRelativePosition = PartPose * vec4(Position, 1.0);
    gl_Position = ProjMat * ModelViewMat * cameraRelativePosition;
    vertexDistance = fog_distance(cameraRelativePosition.xyz, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normalize(PartNormal * Normal), Color);
    lightMapColor = texelFetch(Sampler2, max(UV2, PartLight) / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
