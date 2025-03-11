#version 330 core

in vec2 texCoord;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform vec2 inputSize;
uniform vec2 outputSize;

// Enhanced EASU constants for better performance
const float EASU_EDGE_THRESHOLD = 0.1;
const float EASU_EDGE_POWER = 1.5;
const float EASU_BLOCK_BIAS = 0.8; // Higher values preserve Minecraft's blocky look
const float EASU_SHARPNESS = 0.5;

// Minecraft-specific optimizations
const float MC_EDGE_SNAP = 1.0/16.0; // Snap to Minecraft texture pixel boundaries
const float MC_EDGE_BOOST = 2.0;     // Stronger edge detection for block boundaries
const vec2 MC_BLOCK_SCALE = vec2(16.0, 16.0); // Typical Minecraft texture scale

// Optimized Lanczos parameters
const float PI = 3.14159265359;
const float LANCZOS_WINDOW = 2.0;

// Fast approximation of common math functions
float FastSin(float x) {
    // 7th order polynomial approximation of sin(x*pi)
    // Accurate enough for our use and much faster
    float y = (x * 2.0) - 1.0; // map from [0,1] to [-1,1]
    return -0.02 * y * (y*y - 1.0) * (9.0*y*y - 5.0);
}

// Optimized Lanczos filter
float FastLanczos(float x) {
    if (abs(x) < 0.001) return 1.0;
    if (abs(x) >= LANCZOS_WINDOW) return 0.0;
    
    float xpi = x * PI;
    float sinc = FastSin(0.5 + x*0.5) / xpi;
    float lanc = sinc * FastSin(0.5 + x/LANCZOS_WINDOW*0.5) * LANCZOS_WINDOW;
    
    // Slight bias toward blocky appearance for Minecraft
    return mix(lanc, step(abs(x), 0.5), EASU_BLOCK_BIAS);
}

// Fast luminance calculation
float FastLuma(vec3 color) {
    // Weighted toward green for better perception
    return dot(color, vec3(0.2, 0.7, 0.1));
}

// Optimized gradient detection tailored for Minecraft's block edges
vec2 ComputeBlockEdgeGradient(vec2 uv) {
    vec2 texelSize = 1.0 / inputSize;
    
    // Sample brightness at grid points
    float c0 = FastLuma(texture(inputTexture, uv + vec2(-texelSize.x, -texelSize.y)).rgb);
    float c1 = FastLuma(texture(inputTexture, uv + vec2(0.0, -texelSize.y)).rgb);
    float c2 = FastLuma(texture(inputTexture, uv + vec2(texelSize.x, -texelSize.y)).rgb);
    float c3 = FastLuma(texture(inputTexture, uv + vec2(-texelSize.x, 0.0)).rgb);
    float c4 = FastLuma(texture(inputTexture, uv).rgb);
    float c5 = FastLuma(texture(inputTexture, uv + vec2(texelSize.x, 0.0)).rgb);
    float c6 = FastLuma(texture(inputTexture, uv + vec2(-texelSize.x, texelSize.y)).rgb);
    float c7 = FastLuma(texture(inputTexture, uv + vec2(0.0, texelSize.y)).rgb);
    float c8 = FastLuma(texture(inputTexture, uv + vec2(texelSize.x, texelSize.y)).rgb);
    
    // Calculate gradients with higher weight on direct neighbors
    float gx = (c0 - c2) * 0.5 + (c3 - c5) + (c6 - c8) * 0.5;
    float gy = (c0 - c6) * 0.5 + (c1 - c7) + (c2 - c8) * 0.5;
    
    // Accentuate block edges (common in Minecraft)
    float edginess = max(abs(gx), abs(gy));
    float blockEdgeFactor = smoothstep(0.1, 0.3, edginess) * MC_EDGE_BOOST;
    
    return vec2(gx, gy) * blockEdgeFactor;
}

// Detect if point is on a likely Minecraft block boundary
float DetectBlockGrid(vec2 pos) {
    // Convert to likely texture coordinates in Minecraft's system
    vec2 mcGrid = fract(pos * inputSize / MC_BLOCK_SCALE);
    
    // Check proximity to block edges
    float edgeX = min(mcGrid.x, 1.0 - mcGrid.x);
    float edgeY = min(mcGrid.y, 1.0 - mcGrid.y);
    
    // Return 1.0 if close to an edge, 0.0 otherwise
    return step(min(edgeX, edgeY), MC_EDGE_SNAP);
}

void main() {
    // Calculate relative position from output to input
    vec2 inputTexelSize = 1.0 / inputSize;
    float scaleRatio = inputSize.x / outputSize.x;
    
    // Calculate position in input texture space
    vec2 inputPos = texCoord * outputSize / inputSize;
    
    // Apply half-pixel offset for correct texel center alignment
    inputPos += 0.5 * inputTexelSize;
    
    // Calculate the discrete and fractional parts for sampling
    vec2 texelPos = floor(inputPos * inputSize) / inputSize;
    vec2 texelFract = fract(inputPos * inputSize);
    
    // Check if we're near a likely block grid boundary
    float blockEdge = DetectBlockGrid(texelPos);
    
    // Slightly snap to pixel boundaries for Minecraft's pixel art
    if (blockEdge > 0.0) {
        texelFract = round(texelFract / MC_EDGE_SNAP) * MC_EDGE_SNAP;
    }
    
    // Calculate edge gradient for directed sampling
    vec2 gradient = ComputeBlockEdgeGradient(texelPos);
    float gradientMagnitude = length(gradient);
    
    // Edge detection factor (stronger for Minecraft's block edges)
    float edgeFactor = smoothstep(EASU_EDGE_THRESHOLD, 1.0, gradientMagnitude);
    
    // Determine sampling direction (perpendicular to edge)
    vec2 edgeDir = gradientMagnitude > 0.001 ? normalize(gradient) : vec2(0.0, 1.0);
    vec2 perpDir = vec2(-edgeDir.y, edgeDir.x); // Perpendicular direction
    
    // Sample fewer points for better performance - 3x3 optimized pattern
    vec4 samples[9];
    float weights[9];
    float weightSum = 0.0;
    
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            int idx = (y+1)*3 + (x+1);
            vec2 offset = vec2(float(x), float(y));
            
            // Calculate position relative to fractional part
            vec2 sampleDist = offset - texelFract;
            
            // Sample position in texture coordinates
            vec2 samplePos = texelPos + (offset * inputTexelSize);
            samples[idx] = texture(inputTexture, samplePos);
            
            // Apply Lanczos filtering with directional bias
            float lanczosWeight = FastLanczos(length(sampleDist));
            
            // Apply edge-directed scaling - stretch filter perpendicular to edge
            if (edgeFactor > 0.0) {
                // Calculate alignment with edge direction (higher = better aligned)
                float edgeAlignment = abs(dot(normalize(sampleDist), perpDir));
                
                // Boost weight along the edge, reduce across the edge
                lanczosWeight *= mix(1.0, 2.0 - edgeAlignment, edgeFactor * EASU_EDGE_POWER);
            }
            
            // Boost weights for block edges in Minecraft
            if (blockEdge > 0.0) {
                // If we're on the block grid, boost center samples
                if (x == 0 || y == 0) {
                    lanczosWeight *= 1.2;
                }
            }
            
            weights[idx] = lanczosWeight;
            weightSum += lanczosWeight;
        }
    }
    
    // Normalize weights for proper filtering
    if (weightSum > 0.0) {
        for (int i = 0; i < 9; i++) {
            weights[i] /= weightSum;
        }
    }
    
    // Apply weighted samples
    vec4 color = vec4(0.0);
    for (int i = 0; i < 9; i++) {
        color += samples[i] * weights[i];
    }
    
    // Enhance edges for Minecraft's crisp look
    if (edgeFactor > 0.0 && blockEdge > 0.0) {
        // Center sample with higher weight for edge preservation
        vec4 centerSample = samples[4]; // Center sample
        color = mix(color, centerSample, edgeFactor * EASU_SHARPNESS * blockEdge);
    }
    
    FragColor = color;
} 