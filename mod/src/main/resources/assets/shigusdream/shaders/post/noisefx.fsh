#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float time = GameTime * 240.0;
    float n = hash(floor(texCoord * InSize / 2.0) + floor(time * 30.0));
    vec3 c = texture(InSampler, texCoord).rgb;
    fragColor = vec4(mix(c, vec3(n), 0.45), 1.0);
}
