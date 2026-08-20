#version 330

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform HypnosiaAlphaStrip {
    vec4 StripParams;   // x: width, y: height, z: radius, w: checker size
    vec4 SelectedColor; // RGBA
};

in vec2 localPos;

out vec4 fragColor;

float roundedRectSdf(vec2 point, vec2 halfSize, float radius) {
    vec2 q = abs(point) - halfSize + vec2(radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    vec2 size = max(StripParams.xy, vec2(0.0001));
    float radius = clamp(StripParams.z, 0.0, min(size.x, size.y) * 0.5);
    float checkerSize = max(StripParams.w, 1.0);

    vec2 centered = localPos - size * 0.5;
    float distanceToOuter = roundedRectSdf(centered, size * 0.5, radius);
    float aa = max(fwidth(distanceToOuter), 0.75);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, distanceToOuter);

    vec2 checkerCoord = floor(localPos / checkerSize);
    float checker = mod(checkerCoord.x + checkerCoord.y, 2.0);
    vec3 checkerColor = mix(vec3(0.78), vec3(0.48), checker);

    float alphaT = clamp(localPos.x / size.x, 0.0, 1.0);
    vec4 overlay = vec4(SelectedColor.rgb, SelectedColor.a * alphaT);
    vec3 composited = mix(checkerColor, overlay.rgb, overlay.a);

    vec4 color = vec4(composited, shapeAlpha);
    if (color.a <= 0.001) {
        discard;
    }

    fragColor = color;
}
