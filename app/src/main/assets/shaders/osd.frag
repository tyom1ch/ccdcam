precision mediump float;
uniform sampler2D sAtlas;      // premultiplied: white fill, black outline, transparent elsewhere
varying vec2 vUv;
varying vec4 vColor;

void main() {
    vec4 t = texture2D(sAtlas, vUv);
    gl_FragColor = vec4(t.rgb * vColor.rgb, t.a);
}
