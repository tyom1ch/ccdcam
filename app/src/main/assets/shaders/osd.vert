attribute vec2 aPos;           // LCD uv: 0..1, y up
attribute vec2 aUv;            // atlas texel uv
attribute vec4 aColor;         // multiply colour (fill); outline texels stay black
uniform mat3 uLcdToBuffer;     // upright LCD uv -> output buffer uv (geometry/EffectGeometry.kt)
varying vec2 vUv;
varying vec4 vColor;

void main() {
    vec2 b = (uLcdToBuffer * vec3(aPos, 1.0)).xy;
    gl_Position = vec4(b * 2.0 - 1.0, 0.0, 1.0);
    vUv = aUv;
    vColor = aColor;
}
