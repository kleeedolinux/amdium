#version 330 core

// Input vertex attributes
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aTexCoord;

// Output to fragment shader
out vec2 texCoord;
out vec4 posPos;

// Uniforms for FSR calculations
uniform vec2 inputSize;
uniform vec2 outputSize;

void main() {
    gl_Position = vec4(aPos, 1.0);
    texCoord = aTexCoord;
    
    // Calculate FSR position data with Minecraft-specific optimizations
    // For Minecraft's pixel-perfect textures, we need precise pixel positioning
    vec2 inputPt = aTexCoord * inputSize;
    
    // For Minecraft's blocky style, we want to align to pixel boundaries
    // to prevent blurring across block edges
    vec2 inputPtFloor = floor(inputPt);
    vec2 inputPtFract = fract(inputPt);
    
    // Pack position data for fragment shader
    // xy = normalized input position (pixel-aligned for Minecraft)
    // zw = fractional part for subpixel precision
    posPos = vec4(
        inputPtFloor / inputSize,  // Base position aligned to pixel boundaries
        inputPtFract              // Subpixel offset for precision
    );
    
    // Apply half-pixel offset for correct texel center sampling
    // This is crucial for Minecraft's pixel-perfect textures
    posPos.xy += vec2(0.5) / inputSize;
    
    // For Minecraft's blocky style, we want to ensure pixel-perfect alignment
    // when the scale factor is an integer multiple
    float scaleFactor = outputSize.x / inputSize.x;
    if (abs(round(scaleFactor) - scaleFactor) < 0.01) {
        // For integer scale factors, ensure perfect pixel alignment
        // This prevents blurring across block boundaries
        posPos.zw = round(posPos.zw * 16.0) / 16.0; // Quantize to 1/16 pixel for Minecraft textures
    }
} 