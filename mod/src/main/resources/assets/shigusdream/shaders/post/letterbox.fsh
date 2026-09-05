#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform LetterboxConfig {
    float Bars;
};

out vec4 fragColor;

void main() {
    vec4 c = texture(InSampler, texCoord);
    if (texCoord.y < Bars || texCoord.y > 1.0 - Bars) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        fragColor = vec4(c.rgb, 1.0);
    }
}
