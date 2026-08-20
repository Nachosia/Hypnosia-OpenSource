#version 330

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform HypnosiaBox {
    vec4 BoxParams;   // x: width, y: height, z: radius, w: inner stroke thickness
    vec4 FillColor;   // RGBA, 0.0 - 1.0
    vec4 StrokeColor; // RGBA, 0.0 - 1.0
};

in vec2 localPos;

out vec4 fragColor;

float roundedRectSdf(vec2 point, vec2 halfSize, float radius) {
    vec2 q = abs(point) - halfSize + vec2(radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    vec2 size = max(BoxParams.xy, vec2(0.0001));
    float radius = clamp(BoxParams.z, 0.0, min(size.x, size.y) * 0.5);
    float strokeThickness = clamp(BoxParams.w, 0.0, min(size.x, size.y) * 0.5);

    vec2 centered = localPos - size * 0.5;
    float distanceToOuter = roundedRectSdf(centered, size * 0.5, radius);
    float aa = max(fwidth(distanceToOuter), 0.75);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, distanceToOuter);

    float insideDistance = -distanceToOuter;
    float strokeMask = 0.0;
    if (strokeThickness > 0.001) {
        strokeMask = 1.0 - smoothstep(strokeThickness - aa, strokeThickness + aa, insideDistance);
    }

    vec4 color = mix(FillColor, StrokeColor, strokeMask);
    color.a *= shapeAlpha;

    if (color.a <= 0.001) {
        discard;
    }

    fragColor = color;
}
