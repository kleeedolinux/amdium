#version 330 core

in vec2 texCoord;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform vec2 inputSize;
uniform vec2 outputSize;

// Basic edge detection constants
const float EDGE_THRESHOLD = 0.1;
const float EDGE_SHARPNESS = 0.8;

void main() {
    // Calculate the input texture coordinates
    vec2 inputTexelSize = 1.0 / inputSize;
    
    // Calculate the position in the input texture
    vec2 inputPos = texCoord * outputSize / inputSize;
    
    // Sample the center pixel
    vec4 center = texture(inputTexture, inputPos);
    
    // Sample neighboring pixels
    vec4 top = texture(inputTexture, inputPos + vec2(0.0, inputTexelSize.y));
    vec4 bottom = texture(inputTexture, inputPos - vec2(0.0, inputTexelSize.y));
    vec4 right = texture(inputTexture, inputPos + vec2(inputTexelSize.x, 0.0));
    vec4 left = texture(inputTexture, inputPos - vec2(inputTexelSize.x, 0.0));
    
    // Calculate edge detection
    float dx = length(right.rgb - left.rgb);
    float dy = length(top.rgb - bottom.rgb);
    float edge = smoothstep(0.0, EDGE_THRESHOLD, sqrt(dx * dx + dy * dy));
    
    // Apply bilinear filtering with edge preservation
    vec2 fraction = fract(inputPos * inputSize);
    
    vec4 topLeft = texture(inputTexture, floor(inputPos * inputSize) * inputTexelSize);
    vec4 topRight = texture(inputTexture, (floor(inputPos * inputSize) + vec2(1.0, 0.0)) * inputTexelSize);
    vec4 bottomLeft = texture(inputTexture, (floor(inputPos * inputSize) + vec2(0.0, 1.0)) * inputTexelSize);
    vec4 bottomRight = texture(inputTexture, (floor(inputPos * inputSize) + vec2(1.0, 1.0)) * inputTexelSize);
    
    vec4 bilinear = mix(
        mix(topLeft, topRight, fraction.x),
        mix(bottomLeft, bottomRight, fraction.x),
        fraction.y
    );
    
    // Mix between bilinear and center based on edge detection
    FragColor = mix(bilinear, center, edge * EDGE_SHARPNESS);
} 