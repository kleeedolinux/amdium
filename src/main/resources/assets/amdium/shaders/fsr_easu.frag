#version 330 core

in vec2 texCoord;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform vec2 inputSize;
uniform vec2 outputSize;

// FSR EASU constants
const float FSR_EASU_EDGE_THRESHOLD = 0.125;
const float FSR_EASU_EDGE_STRENGTH = 1.0;
const float FSR_EASU_EDGE_SENSITIVITY = 1.0;
const float FSR_EASU_EDGE_SHARPNESS = 0.5;

// Lanczos filter parameters
const float PI = 3.14159265359;
const float LANCZOS_WINDOW = 2.0;

// Lanczos filter function
float lanczos(float x) {
    if (x == 0.0) return 1.0;
    if (abs(x) >= LANCZOS_WINDOW) return 0.0;
    float xpi = x * PI;
    return LANCZOS_WINDOW * sin(xpi) * sin(xpi / LANCZOS_WINDOW) / (xpi * xpi);
}

// Compute gradient
vec2 computeGradient(vec2 uv) {
    vec2 texelSize = 1.0 / inputSize;
    
    float c0 = texture(inputTexture, uv + vec2(-texelSize.x, -texelSize.y)).g;
    float c1 = texture(inputTexture, uv + vec2(0.0, -texelSize.y)).g;
    float c2 = texture(inputTexture, uv + vec2(texelSize.x, -texelSize.y)).g;
    float c3 = texture(inputTexture, uv + vec2(-texelSize.x, 0.0)).g;
    float c4 = texture(inputTexture, uv).g;
    float c5 = texture(inputTexture, uv + vec2(texelSize.x, 0.0)).g;
    float c6 = texture(inputTexture, uv + vec2(-texelSize.x, texelSize.y)).g;
    float c7 = texture(inputTexture, uv + vec2(0.0, texelSize.y)).g;
    float c8 = texture(inputTexture, uv + vec2(texelSize.x, texelSize.y)).g;
    
    float gx = c0 - c2 + 2.0 * (c3 - c5) + c6 - c8;
    float gy = c0 - c6 + 2.0 * (c1 - c7) + c2 - c8;
    
    return vec2(gx, gy);
}

void main() {
    // Calculate the input texture coordinates
    vec2 inputTexelSize = 1.0 / inputSize;
    vec2 outputTexelSize = 1.0 / outputSize;
    
    // Calculate the position in the input texture
    vec2 inputPos = texCoord * outputSize / inputSize;
    
    // Calculate the texel position
    vec2 texelPos = inputPos * inputSize;
    vec2 texelFract = fract(texelPos);
    
    // Get the base texel coordinate
    vec2 baseTexel = floor(texelPos) * inputTexelSize;
    
    // Sample the 4x4 neighborhood
    vec4 samples[16];
    for (int y = -1; y <= 2; y++) {
        for (int x = -1; x <= 2; x++) {
            vec2 samplePos = baseTexel + vec2(x, y) * inputTexelSize;
            samples[(y+1)*4 + (x+1)] = texture(inputTexture, samplePos);
        }
    }
    
    // Calculate edge detection
    vec2 grad = computeGradient(baseTexel + vec2(0.5) * inputTexelSize);
    float gradMag = length(grad);
    float edgeDetect = smoothstep(FSR_EASU_EDGE_THRESHOLD, 1.0, gradMag);
    
    // Calculate weights for each sample
    float weights[16];
    float weightSum = 0.0;
    
    for (int y = -1; y <= 2; y++) {
        for (int x = -1; x <= 2; x++) {
            float dx = float(x) - texelFract.x;
            float dy = float(y) - texelFract.y;
            
            // Apply Lanczos filter
            float weight = lanczos(dx) * lanczos(dy);
            
            // Apply edge-directed scaling
            if (edgeDetect > 0.0) {
                vec2 dir = normalize(grad);
                float dist = abs(dx * dir.y - dy * dir.x); // Distance to edge line
                weight *= mix(1.0, exp(-dist * FSR_EASU_EDGE_STRENGTH), edgeDetect * FSR_EASU_EDGE_SENSITIVITY);
            }
            
            weights[(y+1)*4 + (x+1)] = weight;
            weightSum += weight;
        }
    }
    
    // Normalize weights
    if (weightSum > 0.0) {
        for (int i = 0; i < 16; i++) {
            weights[i] /= weightSum;
        }
    }
    
    // Apply weights to samples
    vec4 color = vec4(0.0);
    for (int i = 0; i < 16; i++) {
        color += samples[i] * weights[i];
    }
    
    // Apply edge sharpening
    if (edgeDetect > 0.0) {
        vec4 centerColor = samples[5]; // Center sample
        color = mix(color, centerColor, edgeDetect * FSR_EASU_EDGE_SHARPNESS);
    }
    
    FragColor = color;
} 