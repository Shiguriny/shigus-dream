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
    // Джиттер целых строк
    float lineNoise = hash(vec2(floor(texCoord.y * OutSize.y / 3.0), floor(time * 8.0)));
    float shift = (lineNoise - 0.5) * 0.02 * step(0.92, lineNoise);
    // Хроматическое смещение каналов
    float ca = 0.0015 + 0.0015 * hash(vec2(floor(time * 4.0), 1.0));
    vec3 c;
    c.g = texture(InSampler, texCoord + vec2(shift, 0.0)).g;
    c.r = texture(InSampler, texCoord + vec2(shift + ca, 0.0)).r;
    c.b = texture(InSampler, texCoord + vec2(shift - ca, 0.0)).b;
    // Скан-линии
    float scan = 0.88 + 0.12 * sin(texCoord.y * OutSize.y * 3.14159);
    c *= scan;
    // Лёгкий шум
    float n = hash(texCoord * OutSize * 0.5 + vec2(time * 60.0));
    c = mix(c, vec3(n), 0.05);
    fragColor = vec4(c, 1.0);
}
