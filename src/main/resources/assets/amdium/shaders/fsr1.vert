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
    
    // Calculate FSR position data
    // This follows AMD's approach for precise pixel positioning
    vec2 inputPt = aTexCoord * inputSize;
    vec2 inputPtFloor = floor(inputPt);
    vec2 inputPtFract = fract(inputPt);
    
    // Pack position data for fragment shader
    // xy = normalized input position
    // zw = fractional part for subpixel precision
    posPos = vec4(
        inputPtFloor / inputSize,  // Base position
        inputPtFract              // Subpixel offset
    );
    
    // Apply half-pixel offset for correct texel center sampling
    posPos.xy += vec2(0.5) / inputSize;
} 