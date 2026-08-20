#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(std140) uniform HypnosiaTextFade {
    vec4 FadeRect;   // left, top, right, bottom in transformed GUI pixels
    vec4 FadeParams; // x: fade width, y: enabled
};

in vec2 texCoord;
in vec4 vertexColor;
in vec2 screenPos;

out vec4 fragColor;

float fadeAlpha(vec2 point) {
    if (FadeParams.y < 0.5) {
        return 1.0;
    }
    if (point.x < FadeRect.x || point.x > FadeRect.z || point.y < FadeRect.y || point.y > FadeRect.w) {
        return 0.0;
    }

    float fadeWidth = max(FadeParams.x, 0.001);
    float left = smoothstep(FadeRect.x, FadeRect.x + fadeWidth, point.x);
    float right = 1.0 - smoothstep(FadeRect.z - fadeWidth, FadeRect.z, point.x);
    return left * right;
}

void main() {
    float alpha = texture(Sampler0, texCoord).a;
    alpha *= fadeAlpha(screenPos);

    vec4 color = vec4(vertexColor.rgb, vertexColor.a * alpha);
    if (color.a <= 0.001) {
        discard;
    }
    fragColor = color;
}
