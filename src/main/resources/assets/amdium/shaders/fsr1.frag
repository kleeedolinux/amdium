#version 330 core

in vec2 texCoord;
in vec4 posPos;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform vec2 inputSize;
uniform vec2 outputSize;
uniform float sharpness; // 0.0 to 1.0, with 0.8 being default

// FSR constants from AMD's implementation
const float FSR_RCAS_LIMIT = 0.25;
const float FSR_EASU_CONTRAST_BOOST = 1.0;
const float FSR_EASU_EDGE_POWER = 2.2;
const float FSR_EASU_EDGE_SLOPE = 1.0;
const float FSR_EASU_EDGE_THRESHOLD = 0.125;

// Optimized directional sampling weights
const vec2[8] FSR_EASU_WEIGHTS = vec2[8](
    vec2(-1.0, -1.0), vec2(0.0, -1.0), vec2(1.0, -1.0),
    vec2(-1.0,  0.0),                   vec2(1.0,  0.0),
    vec2(-1.0,  1.0), vec2(0.0,  1.0), vec2(1.0,  1.0)
);

// Helper functions
float RGBToLuma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

vec4 LoadInput(vec2 pos) {
    return texture(inputTexture, clamp(pos / inputSize, vec2(0.0), vec2(1.0)));
}

vec3 FsrEasuSample(vec2 pos, vec2 dir) {
    return LoadInput(pos + dir).rgb;
}

float CalcEdgeAttenuation(float edge, float luma) {
    float edgePower = pow(edge, FSR_EASU_EDGE_POWER);
    return mix(1.0, edgePower, FSR_EASU_EDGE_SLOPE * luma);
}

// Edge-Adaptive Spatial Upsampling (EASU)
vec3 ApplyEASU(vec2 pos) {
    vec4 center = LoadInput(pos);
    vec3 colorSum = center.rgb;
    float weightSum = 1.0;
    
    float centerLuma = RGBToLuma(center.rgb);
    float maxLuma = centerLuma;
    float minLuma = centerLuma;
    vec2 maxLumaPos = vec2(0.0);
    vec2 minLumaPos = vec2(0.0);
    
    // Edge detection and directional sampling
    for (int i = 0; i < 8; i++) {
        vec2 samplePos = pos + FSR_EASU_WEIGHTS[i];
        vec3 sampleColor = FsrEasuSample(pos, FSR_EASU_WEIGHTS[i]);
        float sampleLuma = RGBToLuma(sampleColor);
        
        if (sampleLuma > maxLuma) {
            maxLuma = sampleLuma;
            maxLumaPos = FSR_EASU_WEIGHTS[i];
        }
        if (sampleLuma < minLuma) {
            minLuma = sampleLuma;
            minLumaPos = FSR_EASU_WEIGHTS[i];
        }
        
        float weight = 1.0 - abs(sampleLuma - centerLuma) * FSR_EASU_CONTRAST_BOOST;
        weight = max(weight, 0.0);
        
        colorSum += sampleColor * weight;
        weightSum += weight;
    }
    
    return colorSum / weightSum;
}

// Robust Contrast Adaptive Sharpening (RCAS)
vec3 ApplyRCAS(vec3 color, vec2 pos) {
    float centerLuma = RGBToLuma(color);
    vec3 sharpened = color;
    
    float lumaMin = centerLuma;
    float lumaMax = centerLuma;
    
    // Sample neighbors for contrast-adaptive sharpening
    for (int i = 0; i < 4; i++) {
        vec2 offset = vec2(
            float((i & 1) * 2 - 1),
            float((i & 2) - 1)
        ) / inputSize;
        
        vec3 neighborColor = LoadInput(pos + offset).rgb;
        float neighborLuma = RGBToLuma(neighborColor);
        
        lumaMin = min(lumaMin, neighborLuma);
        lumaMax = max(lumaMax, neighborLuma);
    }
    
    // Calculate local contrast and apply sharpening
    float lumaRange = lumaMax - lumaMin;
    float sharpenStrength = min(lumaRange / FSR_RCAS_LIMIT, 1.0) * sharpness;
    
    // Apply sharpening while preserving local contrast
    sharpened = mix(color, 
                    color * (1.0 + sharpenStrength),
                    smoothstep(0.0, FSR_EASU_EDGE_THRESHOLD, lumaRange));
    
    // Ensure we don't exceed the local contrast range
    float finalLuma = RGBToLuma(sharpened);
    if (finalLuma > lumaMax) {
        sharpened *= lumaMax / finalLuma;
    } else if (finalLuma < lumaMin) {
        sharpened *= lumaMin / finalLuma;
    }
    
    return sharpened;
}

void main() {
    // Get position data from vertex shader
    vec2 pos = posPos.xy * inputSize;
    
    // Apply EASU upscaling
    vec3 upscaledColor = ApplyEASU(pos);
    
    // Apply RCAS sharpening
    vec3 finalColor = ApplyRCAS(upscaledColor, pos);
    
    FragColor = vec4(finalColor, 1.0);
} 