#version 330 core

// Input vertex attributes
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aTexCoord;

// Output to fragment shader
out vec2 texCoord;
out vec4 posPos; // For FSR position calculations

// Uniforms for FSR calculations
uniform vec2 inputSize;
uniform vec2 outputSize;

void main() {
    gl_Position = vec4(aPos, 1.0);
    texCoord = aTexCoord;
    
    // Calculate FSR position data
    vec2 inputPt = aTexCoord * inputSize;
    vec2 inputPtFloor = floor(inputPt);
    vec2 inputPtFract = inputPt - inputPtFloor;
    
    // Pack position data for fragment shader
    posPos = vec4(
        aTexCoord,              // xy = texcoord
        inputPtFract            // zw = fractional part of input position
    );
} 