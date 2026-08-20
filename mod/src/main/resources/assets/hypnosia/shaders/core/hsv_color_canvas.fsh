#version 330

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform HypnosiaHsvCanvas {
    vec4 CanvasParams; // x: width, y: height, z: radius, w: hue degrees
};

in vec2 localPos;

out vec4 fragColor;

float roundedRectSdf(vec2 point, vec2 halfSize, float radius) {
    vec2 q = abs(point) - halfSize + vec2(radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

vec3 hsvToRgb(vec3 hsv) {
    vec3 p = abs(fract(hsv.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return hsv.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), hsv.y);
}

void main() {
    vec2 size = max(CanvasParams.xy, vec2(0.0001));
    float radius = clamp(CanvasParams.z, 0.0, min(size.x, size.y) * 0.5);
    float hue = mod(CanvasParams.w, 360.0) / 360.0;

    vec2 centered = localPos - size * 0.5;
    float distanceToOuter = roundedRectSdf(centered, size * 0.5, radius);
    float aa = max(fwidth(distanceToOuter), 0.75);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, distanceToOuter);

    float saturation = clamp(localPos.x / size.x, 0.0, 1.0);
    float value = 1.0 - clamp(localPos.y / size.y, 0.0, 1.0);
    vec3 rgb = hsvToRgb(vec3(hue, saturation, value));

    vec4 color = vec4(rgb, shapeAlpha);
    if (color.a <= 0.001) {
        discard;
    }

    fragColor = color;
}
