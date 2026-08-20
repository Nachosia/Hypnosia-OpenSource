#version 330

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform HypnosiaShadowBox {
    vec4 BoxParams;    // x: original width, y: original height, z: radius, w: spread
    vec4 ShadowParams; // x: blur, y: expanded-quad extent, z/w: reserved
    vec4 ShadowColor;  // RGBA
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
    float spread = max(BoxParams.w, 0.0);
    float blur = max(ShadowParams.x, 0.0);
    float extent = max(ShadowParams.y, 0.0);

    vec2 originalLocal = localPos - vec2(extent);
    vec2 centered = originalLocal - size * 0.5;
    vec2 grownHalfSize = size * 0.5 + vec2(spread);
    float grownRadius = radius + spread;

    float distanceToShadowShape = roundedRectSdf(centered, grownHalfSize, grownRadius);
    float aa = max(fwidth(distanceToShadowShape), 0.75);
    float feather = max(blur, aa);
    float alpha = 1.0 - smoothstep(-aa, feather, distanceToShadowShape);

    vec4 color = ShadowColor;
    color.a *= alpha;

    if (color.a <= 0.001) {
        discard;
    }

    fragColor = color;
}
