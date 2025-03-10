#version 330 core

in vec2 texCoord;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform float sharpness;

// RCAS constants
const float RCAS_LIMIT = 0.25;
const float RCAS_DENOISE = 0.05;

void main() {
    vec2 texelSize = 1.0 / textureSize(inputTexture, 0);
    
    // Sample the center and neighboring pixels
    vec3 center = texture(inputTexture, texCoord).rgb;
    vec3 top = texture(inputTexture, texCoord + vec2(0.0, texelSize.y)).rgb;
    vec3 bottom = texture(inputTexture, texCoord - vec2(0.0, texelSize.y)).rgb;
    vec3 right = texture(inputTexture, texCoord + vec2(texelSize.x, 0.0)).rgb;
    vec3 left = texture(inputTexture, texCoord - vec2(texelSize.x, 0.0)).rgb;
    
    // Calculate luma for each sample
    float centerLuma = dot(center, vec3(0.2126, 0.7152, 0.0722));
    float topLuma = dot(top, vec3(0.2126, 0.7152, 0.0722));
    float bottomLuma = dot(bottom, vec3(0.2126, 0.7152, 0.0722));
    float rightLuma = dot(right, vec3(0.2126, 0.7152, 0.0722));
    float leftLuma = dot(left, vec3(0.2126, 0.7152, 0.0722));
    
    // Calculate min and max luma in the neighborhood
    float minLuma = min(centerLuma, min(min(topLuma, bottomLuma), min(leftLuma, rightLuma)));
    float maxLuma = max(centerLuma, max(max(topLuma, bottomLuma), max(leftLuma, rightLuma)));
    
    // Calculate luma range for local contrast adaptation
    float lumaRange = maxLuma - minLuma;
    
    // Calculate weights based on luma differences
    float topWeight = 1.0 - abs(centerLuma - topLuma) / (lumaRange + RCAS_DENOISE);
    float bottomWeight = 1.0 - abs(centerLuma - bottomLuma) / (lumaRange + RCAS_DENOISE);
    float rightWeight = 1.0 - abs(centerLuma - rightLuma) / (lumaRange + RCAS_DENOISE);
    float leftWeight = 1.0 - abs(centerLuma - leftLuma) / (lumaRange + RCAS_DENOISE);
    
    // Normalize weights
    float totalWeight = topWeight + bottomWeight + rightWeight + leftWeight;
    if (totalWeight > 0.0) {
        topWeight /= totalWeight;
        bottomWeight /= totalWeight;
        rightWeight /= totalWeight;
        leftWeight /= totalWeight;
    }
    
    // Calculate sharpening strength based on local contrast
    float adaptiveSharpness = sharpness * (1.0 - smoothstep(0.0, 1.0, lumaRange / RCAS_LIMIT));
    
    // Apply sharpening
    vec3 sharpened = center;
    sharpened += (center - top) * topWeight * adaptiveSharpness;
    sharpened += (center - bottom) * bottomWeight * adaptiveSharpness;
    sharpened += (center - right) * rightWeight * adaptiveSharpness;
    sharpened += (center - left) * leftWeight * adaptiveSharpness;
    
    // Ensure we don't exceed the local min/max luma (prevents ringing)
    float sharpenedLuma = dot(sharpened, vec3(0.2126, 0.7152, 0.0722));
    if (sharpenedLuma < minLuma) {
        float scale = (centerLuma - minLuma) / (centerLuma - sharpenedLuma);
        sharpened = mix(vec3(minLuma), center, scale);
    } else if (sharpenedLuma > maxLuma) {
        float scale = (maxLuma - centerLuma) / (sharpenedLuma - centerLuma);
        sharpened = mix(vec3(maxLuma), center, scale);
    }
    
    FragColor = vec4(sharpened, texture(inputTexture, texCoord).a);
} 