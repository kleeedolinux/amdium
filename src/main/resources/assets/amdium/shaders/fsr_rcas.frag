#version 330 core

in vec2 texCoord;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform float sharpness; // 0.0 to 2.0 scale, 0.0 = max sharpening, 2.0 = no sharpening

// Enhanced RCAS constants
const float FSR_RCAS_LIMIT = 0.25 - (1.0/16.0); // More accurate limit from AMD implementation
const float FSR_RCAS_DENOISE = 0.05;
const float FSR_RCAS_CONTRAST_BOOST = 1.2; // Boost edges for Minecraft's blocky style

// Minecraft-specific optimizations
const float MC_BLOCK_EDGE_BOOST = 1.5;    // Stronger sharpening on block edges
const float MC_DIAGONAL_DAMPING = 0.7;    // Reduce artifacts on diagonal patterns
const float MC_PIXEL_BOUNDARY_SNAP = 0.0625; // 1/16th pixel for texture alignment

// Fast approximation of exp2
float FastExp2(float x) {
    // Improved approximation for exp2 function
    x = 1.0 + x * 0.6931471805599453; // ln(2) = 0.693...
    x *= x;
    x *= x;
    return x;
}

// Optimized minimal luma calculation
float FastLuma(vec3 color) {
    // Green-focused luma for faster calculation (matches Minecraft's green emphasis)
    return color.g * 0.7 + color.r * 0.2 + color.b * 0.1;
}

// Check if we're on a likely block edge (for Minecraft optimization)
float DetectBlockEdge(float c, float n, float s, float e, float w) {
    // Calculate normalized differences that indicate block boundaries
    float vEdge = abs(n - s) / max(abs(e - w), 0.001);
    float hEdge = abs(e - w) / max(abs(n - s), 0.001);
    
    // Higher values indicate stronger edge alignment with Minecraft's grid
    return max(vEdge, hEdge) > 4.0 ? MC_BLOCK_EDGE_BOOST : 1.0;
}

void main() {
    // Optimized for better texture cache utilization
    vec2 texelSize = 1.0 / textureSize(inputTexture, 0);
    
    // Calculate pixel-perfect position for Minecraft's grid
    vec2 pixelPos = floor(texCoord / texelSize);
    vec2 pixelCenter = (pixelPos + 0.5) * texelSize;
    vec2 subPixelOffset = texCoord - pixelCenter;
    
    // Snap to Minecraft texture boundaries for better alignment
    subPixelOffset = round(subPixelOffset / MC_PIXEL_BOUNDARY_SNAP) * MC_PIXEL_BOUNDARY_SNAP;
    
    // Optimized spatial sampling
    vec3 e = texture(inputTexture, texCoord).rgb; // Center (already cached)
    vec3 n = texture(inputTexture, texCoord + vec2(0.0, texelSize.y)).rgb;
    vec3 s = texture(inputTexture, texCoord - vec2(0.0, texelSize.y)).rgb;
    vec3 w = texture(inputTexture, texCoord - vec2(texelSize.x, 0.0)).rgb;
    vec3 o = texture(inputTexture, texCoord + vec2(texelSize.x, 0.0)).rgb;
    
    // Fast luma calculation focusing on green channel (important for Minecraft's foliage)
    float lE = FastLuma(e);
    float lN = FastLuma(n);
    float lS = FastLuma(s);
    float lW = FastLuma(w);
    float lO = FastLuma(o);
    
    // Min and max of cross pattern (faster than full neighborhood)
    float lMin = min(lE, min(min(lN, lS), min(lW, lO)));
    float lMax = max(lE, max(max(lN, lS), max(lW, lO)));
    
    // Calculate local variance for sharpening adaptation
    float lVar = lMax - lMin;
    
    // Detect if we're on a Minecraft block edge for enhanced edge treatment
    float edgeFactor = DetectBlockEdge(lE, lN, lS, lW, lO);
    
    // Convert from stops to linear sharpening factor (AMD's approach)
    float sharpeningAmount = FastExp2(-sharpness) * FSR_RCAS_CONTRAST_BOOST * edgeFactor;
    
    // Apply local variance limiting to prevent oversharpening
    sharpeningAmount *= 1.0 - smoothstep(0.0, FSR_RCAS_LIMIT, lVar);
    
    // Calculate weights with optimized noise handling (faster than original)
    float wN = 1.0 - abs(lE - lN) / (lVar + FSR_RCAS_DENOISE);
    float wS = 1.0 - abs(lE - lS) / (lVar + FSR_RCAS_DENOISE);
    float wW = 1.0 - abs(lE - lW) / (lVar + FSR_RCAS_DENOISE);
    float wO = 1.0 - abs(lE - lO) / (lVar + FSR_RCAS_DENOISE);
    
    // Normalize weights (all at once for GPU efficiency)
    float wSum = wN + wS + wW + wO;
    if (wSum > 0.0) {
        float wNorm = 1.0 / wSum;
        wN *= wNorm;
        wS *= wNorm;
        wW *= wNorm;
        wO *= wNorm;
    }
    
    // Apply sharpening with fewer instructions
    vec3 sharpened = e + sharpeningAmount * (
        (e - n) * wN +
        (e - s) * wS +
        (e - w) * wW +
        (e - o) * wO
    );
    
    // Fast clamp to prevent ringing artifacts
    float sharpenedLuma = FastLuma(sharpened);
    if (sharpenedLuma < lMin || sharpenedLuma > lMax) {
        // Simple ratio preservation to avoid color shifts
        float ratio = clamp(lE / max(sharpenedLuma, 0.001), 0.0, 2.0);
        sharpened *= ratio;
    }
    
    // Preserve alpha from original texture
    FragColor = vec4(sharpened, texture(inputTexture, texCoord).a);
} 