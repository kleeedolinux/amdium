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

// Optimized weights for directional sampling
const vec2 FSR_EASU_WEIGHTS[4] = vec2[4](
    vec2( 0.0, -1.0),
    vec2(-1.0,  0.0),
    vec2( 1.0,  0.0),
    vec2( 0.0,  1.0)
);

// Helper functions
float RGBToLuma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

vec4 LoadInput(vec2 pos) {
    return texture(inputTexture, clamp(pos / inputSize, vec2(0.0), vec2(1.0)));
}

vec3 FsrEasuTap(vec2 pos, vec2 dir) {
    vec4 c = LoadInput(pos + dir);
    return c.rgb;
}

float CalcEdgeAttenuation(float edge, float luma) {
    float edgePower = pow(edge, FSR_EASU_EDGE_POWER);
    return mix(1.0, edgePower, FSR_EASU_EDGE_SLOPE * luma);
}

void main() {
    // Get position data from vertex shader
    vec2 pos = posPos.xy;
    vec2 posFract = posPos.zw;
    
    // Calculate base position
    vec2 basePos = floor(pos * inputSize) / inputSize;
    
    // Sample center and neighbors
    vec4 center = LoadInput(pos * inputSize);
    vec3 colorSum = center.rgb;
    float weightSum = 1.0;
    
    // Edge detection and directional sampling
    float centerLuma = RGBToLuma(center.rgb);
    float maxLuma = centerLuma;
    float minLuma = centerLuma;
    vec2 maxLumaPos = vec2(0.0);
    vec2 minLumaPos = vec2(0.0);
    
    // Sample in 4 directions and detect edges
    for (int i = 0; i < 4; i++) {
        vec2 samplePos = pos * inputSize + FSR_EASU_WEIGHTS[i];
        vec3 sampleColor = FsrEasuTap(pos * inputSize, FSR_EASU_WEIGHTS[i]);
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
    
    // Calculate edge strength
    float lumaRange = maxLuma - minLuma;
    float edgeStrength = smoothstep(0.0, FSR_EASU_EDGE_THRESHOLD, lumaRange);
    
    // Apply edge-adaptive upscaling
    vec3 upscaledColor = colorSum / weightSum;
    
    // Apply directional sharpening based on edge detection
    vec2 edgeDir = normalize(maxLumaPos - minLumaPos + vec2(0.0001));
    vec3 sharpColor = upscaledColor;
    
    if (edgeStrength > 0.0) {
        vec3 edgeColor1 = FsrEasuTap(pos * inputSize, edgeDir);
        vec3 edgeColor2 = FsrEasuTap(pos * inputSize, -edgeDir);
        
        float edgeAttenuation = CalcEdgeAttenuation(edgeStrength, centerLuma);
        sharpColor = mix(upscaledColor, 
                        (edgeColor1 + edgeColor2) * 0.5, 
                        edgeAttenuation * sharpness);
    }
    
    // Apply RCAS-like sharpening
    float sharpenStrength = min(lumaRange / FSR_RCAS_LIMIT, 1.0) * sharpness;
    vec3 finalColor = mix(upscaledColor, sharpColor, sharpenStrength);
    
    // Ensure we don't exceed the local contrast range
    float finalLuma = RGBToLuma(finalColor);
    if (finalLuma > maxLuma) {
        finalColor *= maxLuma / finalLuma;
    } else if (finalLuma < minLuma) {
        finalColor *= minLuma / finalLuma;
    }
    
    FragColor = vec4(finalColor, center.a);
} 