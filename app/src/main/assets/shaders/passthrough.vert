attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vEffectUv;        // upright picture space (geometry/EffectGeometry.kt)
uniform mat3 uBufferToEffect;  // output buffer uv -> picture uv

void main() {
    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
    vEffectUv = (uBufferToEffect * vec3(aTexCoord.xy, 1.0)).xy;
}
