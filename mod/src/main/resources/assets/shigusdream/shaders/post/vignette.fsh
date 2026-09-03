#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform VignetteConfig {
    float Strength;
};

out vec4 fragColor;

void main() {
    vec2 uv = texCoord - 0.5;
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    uv.x *= aspect;
    float d = length(uv) / length(vec2(0.5 * aspect, 0.5));
    float v = smoothstep(0.45, 1.15, d) * Strength;
    vec4 c = texture(InSampler, texCoord);
    fragColor = vec4(c.rgb * (1.0 - v), 1.0);
}
