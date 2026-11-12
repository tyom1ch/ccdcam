#extension GL_OES_EGL_image_external : require
precision highp float;

varying vec2 vEffectUv;   // upright picture space, 0..1 inside the picture
uniform samplerExternalOES sTexture;
uniform vec2 uResolution;
uniform float uTime;
uniform mat4 uTexMatrix;       // buffer uv -> camera texture (SurfaceOutput.updateTransformMatrix)
uniform mat3 uEffectToBuffer;  // picture uv -> output buffer uv (geometry/EffectGeometry.kt)

// The look is computed in upright picture space: lines/grid follow the image as viewed and the
// picture is pillar/letterboxed to its aspect inside the emulated 16:9 LCD.

// Look parameters, fed every frame from CameraProfile.shader (profile/Profiles.kt);
// tools/sim.py mirrors them.
uniform float uCcdX;
uniform float uCcdY;
uniform float uLines;
uniform float uSmearThreshold;
uniform float uSmearStrength;
uniform float uSmearRange;
uniform float uFlareThreshold;
uniform float uFlareRange;
uniform float uFlareStrength;
uniform float uChromaNoiseAmp;
uniform float uLumaGrainAmp;
uniform float uBlackLift;
uniform vec3  uWarmGrade;
uniform float uSaturation;     // >1 boosts away from gray
uniform float uContrast;       // around mid-gray
uniform float uScanlineAmp;
uniform float uVignetteStrength;
uniform float uChromaShift;
uniform float uHBlur;          // horizontal low-pass, in CCD cells; 0 = original sampling
uniform float uChroma420;      // 0..1: PAL DV 4:2:0 chroma (colour shared by 2x2 cells, BT.601)
uniform float uFade;           // FADER: 0 = picture, 1 = fully faded to uFadeWhite
uniform float uFadeWhite;      // 1 = WHITE FADER, 0 = BLACK FADER
uniform float uNightShot;      // 1 = NightShot look (visual emulation, no IR involved)

// GLSL ES 2.0 needs constant loop bounds, so sample counts stay compile-time constants.
const int   SMEAR_SAMPLES = 8;
const int   FLARE_SAMPLES = 5;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec2 ccdGrid(vec2 uv) {
    vec2 cell = vec2(uCcdX, uCcdY);
    return (floor(uv * cell) + 0.5) / cell;
}

// Picture uv -> buffer uv (uEffectToBuffer) -> camera texture coordinate (uTexMatrix).
vec2 toTex(vec2 p) {
    vec2 buf = (uEffectToBuffer * vec3(p, 1.0)).xy;
    return (uTexMatrix * vec4(buf, 0.0, 1.0)).xy;
}

vec4 sampleAt(vec2 p) { return texture2D(sTexture, toTex(p)); }

// One CCD cell, optionally low-passed horizontally (3 taps, 1-2-1). uHBlur == 0 is exactly
// the original single sample.
vec4 sampleCell(vec2 p) {
    if (uHBlur <= 0.0) return sampleAt(p);
    vec2 d = vec2(uHBlur / uCcdX, 0.0);
    return 0.25 * sampleAt(p - d) + 0.5 * sampleAt(p) + 0.25 * sampleAt(p + d);
}

float brightMask(vec2 uv, float threshold) {
    vec2 cl = clamp(ccdGrid(uv), vec2(0.001), vec2(0.999));
    float l = luma(sampleAt(cl).rgb);
    return smoothstep(threshold, 1.0, l);
}

void main() {
    vec2 baseUv = vEffectUv;
    if (baseUv.x < 0.0 || baseUv.x > 1.0 || baseUv.y < 0.0 || baseUv.y > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);   // pillar/letterbox outside the picture
        return;
    }
    vec2 uv = ccdGrid(baseUv);

    float r = sampleCell(ccdGrid(baseUv + vec2( uChromaShift, 0.0))).r;
    float g = sampleCell(uv).g;
    float b = sampleCell(ccdGrid(baseUv + vec2(-uChromaShift, 0.0))).b;
    vec3 col = vec3(r, g, b);

    // PAL DV 4:2:0: luma per cell, colour averaged over the 2x2 cell block (BT.601 Y'CbCr).
    if (uChroma420 > 0.0) {
        vec2 cell = vec2(uCcdX, uCcdY);
        vec2 blk = (floor(baseUv * cell * 0.5) * 2.0 + 1.0) / cell;   // shared corner of the block
        vec2 d = 0.5 / cell;
        vec3 c = 0.25 * (sampleAt(blk - d).rgb + sampleAt(blk + d).rgb
                       + sampleAt(blk + vec2(d.x, -d.y)).rgb + sampleAt(blk + vec2(-d.x, d.y)).rgb);
        float y = dot(col, vec3(0.299, 0.587, 0.114));
        float cb = dot(c, vec3(-0.168736, -0.331264, 0.5));
        float cr = dot(c, vec3(0.5, -0.418688, -0.081312));
        vec3 sub = vec3(y + 1.402 * cr, y - 0.344136 * cb - 0.714136 * cr, y + 1.772 * cb);
        col = mix(col, sub, uChroma420);
    }

    // Skipping at zero strength is exact: the result would be multiplied by 0.0 anyway.
    if (uSmearStrength > 0.0) {
        float smear = 0.0;
        for (int i = 1; i <= SMEAR_SAMPLES; i++) {
            float t = float(i) / float(SMEAR_SAMPLES);
            float dy = t * uSmearRange;
            float mu = brightMask(vec2(baseUv.x, baseUv.y - dy), uSmearThreshold);
            float md = brightMask(vec2(baseUv.x, baseUv.y + dy), uSmearThreshold);
            smear += max(mu, md) * (1.0 - t);
        }
        smear = smear / float(SMEAR_SAMPLES) * uSmearStrength;
        col += vec3(smear * 0.9, smear * 0.95, smear * 1.1);
    }

    if (uFlareStrength > 0.0) {
        float flare = 0.0;
        for (int i = 1; i <= FLARE_SAMPLES; i++) {
            float t = float(i) / float(FLARE_SAMPLES);
            float dx = t * uFlareRange;
            float ml = brightMask(vec2(baseUv.x - dx, baseUv.y), uFlareThreshold);
            float mr = brightMask(vec2(baseUv.x + dx, baseUv.y), uFlareThreshold);
            flare += max(ml, mr) * (1.0 - t) * (1.0 - t);
        }
        flare = flare / float(FLARE_SAMPLES) * uFlareStrength;
        col += vec3(flare, flare, flare * 1.05);
    }

    float n = (hash(uv * uResolution + uTime) - 0.5) * uChromaNoiseAmp;
    col.r += n * 0.6;
    col.b -= n * 0.6;

    float gr = (hash(uv * uResolution * 2.0 + uTime * 1.3) - 0.5) * uLumaGrainAmp;
    col += gr;

    col = col * (1.0 - uBlackLift) + uBlackLift;
    col *= uWarmGrade;
    col = (col - 0.5) * uContrast + 0.5;
    float l = luma(col);
    col = mix(vec3(l), col, uSaturation);

    // NightShot: monochrome with the cold green-teal cast of the HC90's IR picture
    // (tint sampled from a real NightShot frame, ITmedia 28048), lifted shadows, more noise.
    // Soft-shoulder curve 1 - e^(-2.5 L): shadows rise, highlights roll off instead of clipping.
    if (uNightShot > 0.5) {
        float nl = 1.0 - exp(-2.5 * max(luma(col), 0.0));
        float nn = (hash(uv * uResolution * 1.7 + uTime * 2.1) - 0.5) * 0.10;
        col = vec3(0.70, 1.0, 0.96) * clamp(nl + nn, 0.0, 1.0);
    }

    float scan = (1.0 - uScanlineAmp) + uScanlineAmp * sin(uv.y * uLines * 3.14159);
    col *= scan;

    vec2 q = uv - 0.5;
    col *= 1.0 - dot(q, q) * uVignetteStrength;

    col = mix(col, vec3(uFadeWhite), clamp(uFade, 0.0, 1.0));

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
