#version 330

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform HypnosiaGlassBox {
    vec4 BoxParams;   // x: width, y: height, z: radius, w: stroke thickness
    vec4 TintColor;   // RGBA, surface tint
    vec4 StrokeColor; // RGBA
    vec4 GlowColor;   // RGBA
    vec4 GlassParams; // x: time, y: strength, z: liquid distortion, w: blur radius
};

uniform sampler2D Sampler0;

in vec2 localPos;

out vec4 fragColor;

float roundedRectSdf(vec2 point, vec2 halfSize, float radius) {
    vec2 q = abs(point) - halfSize + vec2(radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

vec3 blurBackdrop(vec2 uv, vec2 texel, float radius) {
    vec3 sum = texture(Sampler0, uv).rgb * 0.20;

    vec2 r1 = texel * radius;
    vec2 r2 = texel * radius * 2.25;
    vec2 r3 = texel * radius * 4.10;

    sum += texture(Sampler0, uv + vec2( r1.x, 0.0)).rgb * 0.09;
    sum += texture(Sampler0, uv + vec2(-r1.x, 0.0)).rgb * 0.09;
    sum += texture(Sampler0, uv + vec2(0.0,  r1.y)).rgb * 0.09;
    sum += texture(Sampler0, uv + vec2(0.0, -r1.y)).rgb * 0.09;

    sum += texture(Sampler0, uv + vec2( r2.x,  r2.y)).rgb * 0.065;
    sum += texture(Sampler0, uv + vec2(-r2.x,  r2.y)).rgb * 0.065;
    sum += texture(Sampler0, uv + vec2( r2.x, -r2.y)).rgb * 0.065;
    sum += texture(Sampler0, uv + vec2(-r2.x, -r2.y)).rgb * 0.065;

    sum += texture(Sampler0, uv + vec2( r3.x, 0.0)).rgb * 0.04;
    sum += texture(Sampler0, uv + vec2(-r3.x, 0.0)).rgb * 0.04;
    sum += texture(Sampler0, uv + vec2(0.0,  r3.y)).rgb * 0.04;
    sum += texture(Sampler0, uv + vec2(0.0, -r3.y)).rgb * 0.04;

    return sum;
}

void main() {
    vec2 size = max(BoxParams.xy, vec2(0.0001));
    float radius = clamp(BoxParams.z, 0.0, min(size.x, size.y) * 0.5);
    float strokeThickness = clamp(BoxParams.w, 0.0, min(size.x, size.y) * 0.5);
    float strength = clamp(GlassParams.y, 0.0, 1.0);
    float distortion = clamp(GlassParams.z, 0.0, 1.0);
    float blurRadius = max(GlassParams.w, 4.0);

    vec2 centered = localPos - size * 0.5;
    float dist = roundedRectSdf(centered, size * 0.5, radius);
    float aa = max(fwidth(dist), 0.75);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, dist);
    if (shapeAlpha <= 0.001) {
        discard;
    }

    float inside = -dist;
    float strokeMask = 0.0;
    if (strokeThickness > 0.001) {
        strokeMask = (1.0 - smoothstep(strokeThickness - aa, strokeThickness + aa, inside)) * shapeAlpha;
    }

    vec2 uv = clamp(localPos / size, vec2(0.0), vec2(1.0));
    vec2 sourceSize = vec2(textureSize(Sampler0, 0));
    vec2 texel = 1.0 / max(sourceSize, vec2(1.0));
    vec2 screenUv = clamp(gl_FragCoord.xy * texel, texel * 2.0, vec2(1.0) - texel * 2.0);

    // Edge-driven lens displacement. Transparent mode keeps this at zero: it only
    // blurs what is behind the panel. Liquid Glass enables the SDF edge refraction,
    // so the sides distort the world like a thick rounded glass sheet.
    vec2 lensVector = (uv - 0.5) * vec2(size.x / max(size.y, 1.0), 1.0);
    float lens = 1.0 - smoothstep(0.08, 1.05, length(lensVector));
    float edge = 1.0 - smoothstep(0.0, max(10.0, radius * 0.75), inside);
    float cornerLens = pow(lens, 1.45) * 0.35 + edge * 0.95;
    vec2 wave = vec2(
        sin(uv.y * 18.0 + GlassParams.x * 0.72),
        cos(uv.x * 14.0 + GlassParams.x * 0.58)
    ) * (0.0007 + 0.0014 * strength) * distortion;
    vec2 radial = normalize((uv - 0.5) * vec2(size.x / max(size.y, 1.0), 1.0) + vec2(0.0001));
    vec2 refractOffset = (radial * (0.0040 + 0.0170 * cornerLens) + wave) * strength * distortion;
    vec3 backdrop = blurBackdrop(screenUv + refractOffset, texel, blurRadius);

    // Frosted glass lowers contrast and pulls the sampled world toward a calm grey-brown
    // tone before tinting. This avoids the "clear transparent overlay" look.
    float luma = dot(backdrop, vec3(0.2126, 0.7152, 0.0722));
    vec3 softened = mix(vec3(luma), backdrop, mix(0.56, 0.36, strength));
    softened = mix(softened, vec3(0.44, 0.42, 0.40), 0.10 * strength);

    float tintAmount = clamp(TintColor.a * mix(0.18, 0.42, strength), 0.0, 0.62);
    vec3 color = mix(softened, TintColor.rgb, tintAmount);

    float topHighlight = (1.0 - smoothstep(0.0, 0.25, uv.y)) * smoothstep(0.0, 12.0, inside);
    float innerEdge = 1.0 - smoothstep(0.0, 8.0, inside);
    float diagonalSheen = smoothstep(-0.10, 0.68, uv.x - uv.y * 0.34) *
        (1.0 - smoothstep(0.48, 0.92, uv.y));
    float liquidStreak = exp(-pow((uv.y - (0.16 + sin(uv.x * 6.0 + GlassParams.x * 0.45) * 0.018)) * 32.0, 2.0)) *
        smoothstep(0.02, 0.32, uv.x) * (1.0 - smoothstep(0.64, 1.0, uv.x));
    float bottomDepth = smoothstep(0.62, 1.0, uv.y);
    float grain = fract(sin(dot(gl_FragCoord.xy + vec2(GlassParams.x * 37.0), vec2(12.9898, 78.233))) * 43758.5453);

    color += GlowColor.rgb * (topHighlight * 0.120 + innerEdge * (0.065 + 0.075 * distortion) + diagonalSheen * 0.030 + liquidStreak * 0.050 * distortion) * strength;
    color += vec3(grain - 0.5) * 0.010 * strength;
    color -= vec3(bottomDepth * 0.026) * strength;

    vec4 finalColor = vec4(color, shapeAlpha);
    vec4 subtleStroke = vec4(StrokeColor.rgb, StrokeColor.a * shapeAlpha);
    finalColor = mix(finalColor, subtleStroke, strokeMask * clamp(StrokeColor.a * 1.65, 0.0, 0.78));

    fragColor = finalColor;
}
