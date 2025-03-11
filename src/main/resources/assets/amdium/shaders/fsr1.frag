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

// Minecraft-specific edge detection constants
const float MC_EDGE_THRESHOLD = 0.05;    // Lower threshold to catch subtle block edges
const float MC_EDGE_BOOST = 1.5;         // Boost edge detection for Minecraft's blocky style
const float MC_CORNER_BOOST = 2.0;       // Extra boost for corners (where blocks meet)
const float MC_SATURATION_WEIGHT = 0.3;  // Weight for saturation in edge detection

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

// Calculate color saturation - useful for detecting Minecraft textures
float Saturation(vec3 color) {
    float minChannel = min(min(color.r, color.g), color.b);
    float maxChannel = max(max(color.r, color.g), color.b);
    return maxChannel > 0.0 ? (maxChannel - minChannel) / maxChannel : 0.0;
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

// Minecraft-optimized edge detection
float DetectMinecraftEdge(vec3 center, vec3 neighbor) {
    float lumaDiff = abs(RGBToLuma(center) - RGBToLuma(neighbor));
    float satDiff = abs(Saturation(center) - Saturation(neighbor));
    
    // Combine luma and saturation differences for better Minecraft edge detection
    return lumaDiff + satDiff * MC_SATURATION_WEIGHT;
}

// Edge-Adaptive Spatial Upsampling (EASU) optimized for Minecraft
vec3 ApplyEASU(vec2 pos) {
    vec4 center = LoadInput(pos);
    vec3 colorSum = center.rgb;
    float weightSum = 1.0;
    
    float centerLuma = RGBToLuma(center.rgb);
    float centerSat = Saturation(center.rgb);
    float maxLuma = centerLuma;
    float minLuma = centerLuma;
    vec2 maxLumaPos = vec2(0.0);
    vec2 minLumaPos = vec2(0.0);
    
    // Track potential block edges
    float maxEdgeStrength = 0.0;
    vec2 primaryEdgeDir = vec2(0.0);
    
    // Edge detection and directional sampling
    for (int i = 0; i < 8; i++) {
        vec2 samplePos = pos + FSR_EASU_WEIGHTS[i];
        vec3 sampleColor = FsrEasuSample(pos, FSR_EASU_WEIGHTS[i]);
        float sampleLuma = RGBToLuma(sampleColor);
        
        // Track min/max luma for contrast preservation
        if (sampleLuma > maxLuma) {
            maxLuma = sampleLuma;
            maxLumaPos = FSR_EASU_WEIGHTS[i];
        }
        if (sampleLuma < minLuma) {
            minLuma = sampleLuma;
            minLumaPos = FSR_EASU_WEIGHTS[i];
        }
        
        // Minecraft-optimized edge detection
        float edgeStrength = DetectMinecraftEdge(center.rgb, sampleColor);
        
        // Boost corners (diagonal directions)
        if (i == 0 || i == 2 || i == 5 || i == 7) {
            edgeStrength *= MC_CORNER_BOOST;
        }
        
        // Track strongest edge direction
        if (edgeStrength > maxEdgeStrength) {
            maxEdgeStrength = edgeStrength;
            primaryEdgeDir = FSR_EASU_WEIGHTS[i];
        }
        
        // Calculate adaptive weight based on edge detection
        float weight = 1.0 - edgeStrength * MC_EDGE_BOOST;
        weight = max(weight, 0.1); // Ensure some contribution from all samples
        
        colorSum += sampleColor * weight;
        weightSum += weight;
    }
    
    // Apply additional sampling along detected primary edge
    if (maxEdgeStrength > MC_EDGE_THRESHOLD) {
        // Sample perpendicular to the edge for better preservation
        vec2 perpDir = vec2(-primaryEdgeDir.y, primaryEdgeDir.x);
        vec3 edge1 = FsrEasuSample(pos, perpDir);
        vec3 edge2 = FsrEasuSample(pos, -perpDir);
        
        // Add edge samples with high weight to preserve block edges
        float edgeWeight = 2.0 * smoothstep(MC_EDGE_THRESHOLD, 0.2, maxEdgeStrength);
        colorSum += (edge1 + edge2) * edgeWeight;
        weightSum += edgeWeight * 2.0;
    }
    
    return colorSum / weightSum;
}

// Robust Contrast Adaptive Sharpening (RCAS) optimized for Minecraft
vec3 ApplyRCAS(vec3 color, vec2 pos) {
    float centerLuma = RGBToLuma(color);
    vec3 sharpened = color;
    
    float lumaMin = centerLuma;
    float lumaMax = centerLuma;
    
    // Track edge directions for Minecraft's blocky style
    float horizontalEdge = 0.0;
    float verticalEdge = 0.0;
    
    // Sample neighbors for contrast-adaptive sharpening
    for (int i = 0; i < 4; i++) {
        // Use cardinal directions for better block edge detection
        vec2 offset;
        if (i == 0) offset = vec2(0, -1);      // North
        else if (i == 1) offset = vec2(-1, 0); // West
        else if (i == 2) offset = vec2(1, 0);  // East
        else offset = vec2(0, 1);              // South
        
        offset /= inputSize;
        
        vec3 neighborColor = LoadInput(pos + offset).rgb;
        float neighborLuma = RGBToLuma(neighborColor);
        
        // Track min/max for contrast preservation
        lumaMin = min(lumaMin, neighborLuma);
        lumaMax = max(lumaMax, neighborLuma);
        
        // Detect horizontal and vertical edges (common in Minecraft)
        float edgeDiff = abs(centerLuma - neighborLuma);
        if (i < 2) verticalEdge += edgeDiff;
        else horizontalEdge += edgeDiff;
    }
    
    // Calculate local contrast and apply sharpening
    float lumaRange = lumaMax - lumaMin;
    
    // Boost sharpening along detected block edges
    float edgeAlignment = max(horizontalEdge, verticalEdge);
    float blockEdgeBoost = 1.0 + smoothstep(MC_EDGE_THRESHOLD, 0.2, edgeAlignment);
    
    float sharpenStrength = min(lumaRange / FSR_RCAS_LIMIT, 1.0) * sharpness * blockEdgeBoost;
    
    // Apply directional sharpening based on edge detection
    if (horizontalEdge > verticalEdge * 1.5) {
        // Horizontal edge - sharpen vertically
        vec3 north = LoadInput(pos + vec2(0, -1) / inputSize).rgb;
        vec3 south = LoadInput(pos + vec2(0, 1) / inputSize).rgb;
        sharpened = mix(color, color * 2.0 - (north + south) * 0.5, sharpenStrength * 0.5);
    } 
    else if (verticalEdge > horizontalEdge * 1.5) {
        // Vertical edge - sharpen horizontally
        vec3 west = LoadInput(pos + vec2(-1, 0) / inputSize).rgb;
        vec3 east = LoadInput(pos + vec2(1, 0) / inputSize).rgb;
        sharpened = mix(color, color * 2.0 - (west + east) * 0.5, sharpenStrength * 0.5);
    }
    else {
        // No strong directional edge - apply uniform sharpening
        sharpened = mix(color, 
                        color * (1.0 + sharpenStrength),
                        smoothstep(0.0, FSR_EASU_EDGE_THRESHOLD, lumaRange));
    }
    
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
    vec2 subpixelOffset = posPos.zw;
    
    // Apply subpixel offset for better precision on Minecraft's pixel-perfect textures
    pos += subpixelOffset;
    
    // Apply EASU upscaling optimized for Minecraft
    vec3 upscaledColor = ApplyEASU(pos);
    
    // Apply RCAS sharpening optimized for Minecraft
    vec3 finalColor = ApplyRCAS(upscaledColor, pos);
    
    // Preserve alpha from original texture
    float alpha = LoadInput(pos).a;
    FragColor = vec4(finalColor, alpha);
} 