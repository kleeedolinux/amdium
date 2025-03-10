#version 330 core

in vec2 texCoord;
out vec4 FragColor;

uniform sampler2D currentFrame;
uniform sampler2D previousFrame;
uniform sampler2D motionVectors;
uniform float frameTime;
uniform int strength;

// Frame generation constants
const float MOTION_SCALE = 10.0;
const float BLEND_FACTOR_MIN = 0.4;
const float BLEND_FACTOR_MAX = 0.6;
const float MOTION_THRESHOLD = 0.05;
const int SEARCH_STEPS = 8;

// Optical flow estimation
vec2 estimateMotion(vec2 uv) {
    // For a real implementation, we would use the motion vectors from the game
    // Since we don't have direct access to those, we'll estimate them
    
    vec2 texelSize = 1.0 / textureSize(currentFrame, 0);
    float bestMatch = 1e10;
    vec2 bestOffset = vec2(0.0);
    
    // Get the current pixel color
    vec3 centerColor = texture(currentFrame, uv).rgb;
    
    // Search in a small neighborhood for the best match in the previous frame
    for (int y = -SEARCH_STEPS; y <= SEARCH_STEPS; y++) {
        for (int x = -SEARCH_STEPS; x <= SEARCH_STEPS; x++) {
            vec2 offset = vec2(x, y) * texelSize * 0.5;
            vec3 prevColor = texture(previousFrame, uv + offset).rgb;
            
            // Calculate color difference
            vec3 diff = centerColor - prevColor;
            float error = dot(diff, diff);
            
            if (error < bestMatch) {
                bestMatch = error;
                bestOffset = offset;
            }
        }
    }
    
    // If the best match is too different, don't use motion
    if (bestMatch > MOTION_THRESHOLD) {
        return vec2(0.0);
    }
    
    return bestOffset / frameTime;
}

// Temporal reprojection
vec4 temporalReproject(vec2 uv, vec2 motion) {
    // Sample the current frame
    vec4 currentColor = texture(currentFrame, uv);
    
    // Calculate the position in the previous frame
    vec2 prevUV = uv - motion * frameTime;
    
    // Check if the previous position is within bounds
    if (prevUV.x < 0.0 || prevUV.x > 1.0 || prevUV.y < 0.0 || prevUV.y > 1.0) {
        return currentColor;
    }
    
    // Sample the previous frame
    vec4 previousColor = texture(previousFrame, prevUV);
    
    // Calculate the blend factor based on motion magnitude
    float motionLength = length(motion);
    float blendFactor = mix(BLEND_FACTOR_MIN, BLEND_FACTOR_MAX, 
                           smoothstep(0.0, MOTION_SCALE, motionLength));
    
    // Adjust blend factor based on user strength setting (1-10)
    blendFactor *= float(strength) / 5.0;
    
    // Blend the current and previous frames
    return mix(previousColor, currentColor, blendFactor);
}

void main() {
    // Try to get motion vectors from the game (if available)
    vec2 motion = texture(motionVectors, texCoord).rg;
    
    // If no motion vectors are available, estimate them
    if (length(motion) < 0.001) {
        motion = estimateMotion(texCoord);
    }
    
    // Apply temporal reprojection
    vec4 result = temporalReproject(texCoord, motion);
    
    FragColor = result;
} 