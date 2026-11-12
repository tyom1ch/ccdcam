attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTexCoord;
varying vec2 vEffectUv;        // upright picture space, for uEffectSpace = 1 in ccd.frag
uniform mat4 uTexMatrix;
uniform float uStretch;        // <1.0 zooms in horizontally (anamorphic CCD squish)
uniform float uRotationRad;    // applied to gl_Position; 0 for display, ±π/2 for landscape encoder
uniform mat3 uBufferToEffect;  // output buffer uv -> picture uv (geometry/EffectGeometry.kt)

void main() {
    float c = cos(uRotationRad), s = sin(uRotationRad);
    vec2 p = aPosition.xy;
    gl_Position = vec4(c * p.x - s * p.y, s * p.x + c * p.y, 0.0, 1.0);
    vec2 base = (uTexMatrix * aTexCoord).xy;
    vTexCoord = vec2(0.5 + (base.x - 0.5) * uStretch, base.y);
    vEffectUv = (uBufferToEffect * vec3(aTexCoord.xy, 1.0)).xy;
}
