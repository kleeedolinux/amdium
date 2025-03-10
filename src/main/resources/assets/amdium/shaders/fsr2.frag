#version 330 core

in vec2 texCoord;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform sampler2D historyTexture;
uniform vec2 inputSize;
uniform vec2 outputSize;
uniform float frameTime;

// FSR2 constants
const float TEMPORAL_WEIGHT = 0.9;
const float MOTION_SCALE = 5.0;
const float EDGE_THRESHOLD = 0.1;

// Simple motion estimation
vec2 estimateMotion(vec2 uv) {
    vec2 texelSize = 1.0 / inputSize;
    vec3 center = texture(inputTexture, uv).rgb;
    
    // Check in 8 directions
    vec2 directions[8] = vec2[](
        vec2(-1, -1), vec2(0, -1), vec2(1, -1),
        vec2(-1, 0),               vec2(1, 0),
        vec2(-1, 1),  vec2(0, 1),  vec2(1, 1)
    );
    
    float bestDiff = 1.0;
    vec2 bestDir = vec2(0.0);
    
    for (int i = 0; i < 8; i++) {
        vec2 dir = directions[i] * texelSize * 2.0;
        vec3 neighborColor = texture(inputTexture, uv + dir).rgb;
        float diff = length(center - neighborColor);
        
        if (diff < bestDiff) {
            bestDiff = diff;
            bestDir = dir;
        }
    }
    
    return bestDir / frameTime;
}

void main() {
    // Calculate the input texture coordinates
    vec2 inputTexelSize = 1.0 / inputSize;
    vec2 outputTexelSize = 1.0 / outputSize;
    
    // Calculate the position in the input texture
    vec2 inputPos = texCoord * outputSize / inputSize;
    
    // Estimate motion
    vec2 motion = estimateMotion(inputPos);
    
    // Calculate previous frame position
    vec2 prevPos = texCoord - motion * frameTime * outputTexelSize;
    
    // Sample current and previous frames
    vec4 current = texture(inputTexture, inputPos);
    vec4 history = texture(historyTexture, prevPos);
    
    // Edge detection for current frame
    vec4 top = texture(inputTexture, inputPos + vec2(0.0, inputTexelSize.y));
    vec4 bottom = texture(inputTexture, inputPos - vec2(0.0, inputTexelSize.y));
    vec4 right = texture(inputTexture, inputPos + vec2(inputTexelSize.x, 0.0));
    vec4 left = texture(inputTexture, inputPos - vec2(inputTexelSize.x, 0.0));
    
    float dx = length(right.rgb - left.rgb);
    float dy = length(top.rgb - bottom.rgb);
    float edge = smoothstep(0.0, EDGE_THRESHOLD, sqrt(dx * dx + dy * dy));
    
    // Calculate temporal weight based on motion and edges
    float temporalWeight = TEMPORAL_WEIGHT * (1.0 - edge) * (1.0 - min(1.0, length(motion) / MOTION_SCALE));
    
    // Clamp history to reduce ghosting
    vec3 minColor = min(min(top.rgb, bottom.rgb), min(left.rgb, right.rgb));
    vec3 maxColor = max(max(top.rgb, bottom.rgb), max(left.rgb, right.rgb));
    history.rgb = clamp(history.rgb, minColor, maxColor);
    
    // Temporal accumulation
    FragColor = mix(current, history, temporalWeight);
} 