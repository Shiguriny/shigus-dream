#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GrayConfig {
    float Amount;
};

out vec4 fragColor;

void main() {
    vec4 c = texture(InSampler, texCoord);
    float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(mix(c.rgb, vec3(luma), Amount), 1.0);
}
