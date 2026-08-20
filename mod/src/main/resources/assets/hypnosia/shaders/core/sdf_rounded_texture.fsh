#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(std140) uniform HypnosiaTextureBox {
    vec4 TextureParams; // x: width, y: height, z: radius, w: icon mask mode
    vec4 TintColor;     // RGBA
};

in vec2 localPos;

out vec4 fragColor;

float roundedRectSdf(vec2 point, vec2 halfSize, float radius) {
    vec2 q = abs(point) - halfSize + vec2(radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    vec2 size = max(TextureParams.xy, vec2(0.0001));
    float radius = clamp(TextureParams.z, 0.0, min(size.x, size.y) * 0.5);

    vec2 centered = localPos - size * 0.5;
    float distanceToOuter = roundedRectSdf(centered, size * 0.5, radius);
    float aa = max(fwidth(distanceToOuter), 0.75);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, distanceToOuter);

    vec2 uv = clamp(localPos / size, vec2(0.0), vec2(1.0));
    vec4 sampled = texture(Sampler0, uv);
    if (TextureParams.w > 0.5) {
        sampled = vec4(TintColor.rgb, sampled.a * TintColor.a);
    } else {
        sampled *= TintColor;
    }
    sampled.a *= shapeAlpha;

    if (sampled.a <= 0.001) {
        discard;
    }

    fragColor = sampled;
}
