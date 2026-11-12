attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vUv;              // output buffer uv

void main() {
    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
    vUv = aTexCoord.xy;
}
