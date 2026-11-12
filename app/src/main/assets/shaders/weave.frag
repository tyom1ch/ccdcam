precision highp float;
varying vec2 vUv;
uniform sampler2D sCur;        // this frame (rendered look)
uniform sampler2D sPrev;       // previous frame
uniform mat3 uBufferToEffect;  // buffer uv -> upright picture uv
uniform float uLines;          // picture lines (576 for PAL)
uniform float uBlend;          // >0: Super NightShot slow shutter: mix(prev, cur, uBlend), no weave

// PAL DV is interlaced: the two fields of a frame are 20 ms apart. Weave: even picture lines
// from this frame, odd lines from the previous one -> clean when still, combing on motion.
// Lines are counted in upright picture space, so the comb is horizontal in any orientation.
void main() {
    if (uBlend > 0.0) {
        gl_FragColor = mix(texture2D(sPrev, vUv), texture2D(sCur, vUv), uBlend);
        return;
    }
    vec2 p = (uBufferToEffect * vec3(vUv, 1.0)).xy;
    float odd = mod(floor(p.y * uLines), 2.0);
    gl_FragColor = odd >= 1.0 ? texture2D(sPrev, vUv) : texture2D(sCur, vUv);
}
