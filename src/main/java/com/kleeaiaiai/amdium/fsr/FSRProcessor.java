package com.kleeaiaiai.amdium.fsr;

import com.kleeaiaiai.amdium.AMDium;
import com.kleeaiaiai.amdium.config.AMDiumConfig;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.stream.Collectors;

import net.minecraft.client.MinecraftClient;

public class FSRProcessor {
    // Static fullscreen quad resources
    private static int quadVAO = -1;
    private static int quadVBO = -1;
    private static boolean quadInitialized = false;
    
    // Maximum texture size supported by the GPU
    private static int maxTextureSize = -1;
    
    // Shader programs for FSR 1.0
    private int fsr1ShaderProgram;
    
    private int inputFramebuffer;
    private int upscaledFramebuffer;
    private int outputFramebuffer;
    
    private int inputTexture;
    private int upscaledTexture;
    private int outputTexture;
    private int depthTexture;
    
    private int displayWidth;
    private int displayHeight;
    private int renderWidth;
    private int renderHeight;
    
    private boolean initialized = false;
    private boolean shadersCompiled = false;
    
    // FSR quality settings
    private float sharpness = 0.8f; // Default sharpness value
    
    // Error tracking
    private int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    
    /**
     * Get the maximum texture size supported by the GPU
     */
    private int getMaxTextureSize() {
        if (maxTextureSize == -1) {
            maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            AMDium.LOGGER.info("Maximum texture size: " + maxTextureSize);
        }
        return maxTextureSize;
    }
    
    /**
     * Validate texture dimensions to ensure they're within GPU limits
     */
    private boolean validateTextureDimensions(int width, int height) {
        int maxSize = getMaxTextureSize();
        if (width <= 0 || height <= 0) {
            AMDium.LOGGER.error("Invalid texture dimensions: " + width + "x" + height);
            return false;
        }
        if (width > maxSize || height > maxSize) {
            AMDium.LOGGER.error("Texture dimensions exceed maximum size: " + width + "x" + height + " > " + maxSize);
            return false;
        }
        return true;
    }
    
    /**
     * Create a texture with validation and error handling
     */
    private int createTexture(int width, int height, int internalFormat, int format, int type) {
        if (!validateTextureDimensions(width, height)) {
            return 0;
        }
        
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        
        try {
            // Allocate texture storage
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer) null);
            
            // Set texture parameters for FSR
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            
            // Check for OpenGL errors
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                AMDium.LOGGER.error("OpenGL error creating texture: " + error);
                GL11.glDeleteTextures(texture);
                return 0;
            }
            
            AMDium.LOGGER.debug("Created texture " + texture + " with dimensions " + width + "x" + height);
            return texture;
        } catch (Exception e) {
            AMDium.LOGGER.error("Failed to create texture", e);
            GL11.glDeleteTextures(texture);
            return 0;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
    }
    
    public void initialize() {
        if (initialized) {
            AMDium.LOGGER.warn("FSR Processor already initialized");
            return;
        }
        
        try {
            AMDium.LOGGER.info("Initializing FSR Processor");
            
            // Get display and render dimensions
            MinecraftClient mc = MinecraftClient.getInstance();
            displayWidth = mc.getWindow().getWidth();
            displayHeight = mc.getWindow().getHeight();
            
            // Calculate render dimensions based on scaling factor
            float scalingFactor = AMDium.getInstance().getConfig().getScalingFactor();
            renderWidth = Math.round(displayWidth * scalingFactor);
            renderHeight = Math.round(displayHeight * scalingFactor);
            
            AMDium.LOGGER.info("FSR dimensions: " + renderWidth + "x" + renderHeight + 
                              " -> " + displayWidth + "x" + displayHeight);
            
            // Validate dimensions
            if (!validateTextureDimensions(renderWidth, renderHeight) || 
                !validateTextureDimensions(displayWidth, displayHeight)) {
                AMDium.LOGGER.error("Invalid dimensions for FSR");
                return;
            }
            
            // Initialize fullscreen quad if needed
            if (!quadInitialized) {
                initializeQuad();
            }
            
            // Compile shaders
            if (!shadersCompiled) {
                try {
                    compileShaders();
                } catch (Exception e) {
                    AMDium.LOGGER.error("Failed to compile FSR shaders", e);
                    return;
                }
            }
            
            // Create framebuffers and textures
            try {
                createFramebuffers();
            } catch (Exception e) {
                AMDium.LOGGER.error("Failed to create FSR framebuffers", e);
                cleanup(); // Clean up any partially created resources
                return;
            }
            
            // Verify framebuffers are complete
            if (!verifyFramebuffers()) {
                AMDium.LOGGER.error("FSR framebuffers verification failed");
                cleanup();
                return;
            }
            
            // Set initial sharpness from config
            sharpness = AMDium.getInstance().getConfig().getSharpness();
            
            initialized = true;
            AMDium.LOGGER.info("FSR Processor initialized successfully");
        } catch (Exception e) {
            AMDium.LOGGER.error("Failed to initialize FSR Processor", e);
            cleanup(); // Clean up any partially created resources
        }
    }
    
    public void cleanup() {
        if (!initialized) return;
        
        try {
            // Make sure we're not in the middle of rendering
            GL30.glFinish();
            
            // Delete shader programs if they exist
            if (fsr1ShaderProgram > 0) {
                GL20.glDeleteProgram(fsr1ShaderProgram);
                fsr1ShaderProgram = 0;
            }
            
            // Delete framebuffers if they exist
            deleteFramebuffer(inputFramebuffer);
            deleteFramebuffer(upscaledFramebuffer);
            deleteFramebuffer(outputFramebuffer);
            deleteFramebuffer(depthTexture);
            
            // Delete textures if they exist
            deleteTexture(inputTexture);
            deleteTexture(upscaledTexture);
            deleteTexture(outputTexture);
            deleteTexture(depthTexture);
            
            // Reset to default framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            
            // Reset state
            initialized = false;
            
            AMDium.LOGGER.info("FSR processor cleaned up successfully");
        } catch (Exception e) {
            AMDium.LOGGER.error("Error during FSR processor cleanup", e);
            // Make sure we're on the default framebuffer even if cleanup fails
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }
    
    private void deleteFramebuffer(int framebuffer) {
        if (framebuffer > 0 && GL30.glIsFramebuffer(framebuffer)) {
            GL30.glDeleteFramebuffers(framebuffer);
        }
    }
    
    private void deleteTexture(int texture) {
        if (texture > 0 && GL11.glIsTexture(texture)) {
            GL11.glDeleteTextures(texture);
        }
    }
    
    public void resizeBuffers(int width, int height) {
        if (!initialized) return;
        
        try {
            AMDiumConfig config = AMDium.getInstance().getConfig();
            FSRQualityMode qualityMode = config.getQualityMode();
            
            // Store old values to check if we actually need to resize
            int oldDisplayWidth = displayWidth;
            int oldDisplayHeight = displayHeight;
            int oldRenderWidth = renderWidth;
            int oldRenderHeight = renderHeight;
            
            // Calculate new dimensions
            displayWidth = width;
            displayHeight = height;
            renderWidth = qualityMode.calculateRenderWidth(width);
            renderHeight = qualityMode.calculateRenderHeight(height);
            
            // Only recreate framebuffers if dimensions have actually changed
            if (oldDisplayWidth != displayWidth || oldDisplayHeight != displayHeight ||
                oldRenderWidth != renderWidth || oldRenderHeight != renderHeight) {
                
                // Delete old framebuffers and textures
                deleteFramebuffer(inputFramebuffer);
                deleteFramebuffer(upscaledFramebuffer);
                deleteFramebuffer(outputFramebuffer);
                deleteFramebuffer(depthTexture);
                
                deleteTexture(inputTexture);
                deleteTexture(upscaledTexture);
                deleteTexture(outputTexture);
                deleteTexture(depthTexture);
                
                // Reset framebuffer and texture IDs
                inputFramebuffer = 0;
                upscaledFramebuffer = 0;
                outputFramebuffer = 0;
                depthTexture = 0;
                
                inputTexture = 0;
                upscaledTexture = 0;
                outputTexture = 0;
                depthTexture = 0;
                
                // Create new framebuffers with the new size
                createFramebuffers();
                
                AMDium.LOGGER.info("FSR buffers resized to " + renderWidth + "x" + renderHeight + 
                                  " -> " + displayWidth + "x" + displayHeight);
            }
        } catch (Exception e) {
            AMDium.LOGGER.error("Error resizing FSR buffers", e);
            // Reset to default framebuffer if there's an error
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }
    
    private void createFramebuffers() {
        try {
            // Make sure dimensions are valid
            if (!validateTextureDimensions(renderWidth, renderHeight) || 
                !validateTextureDimensions(displayWidth, displayHeight)) {
                throw new IllegalStateException("Invalid framebuffer dimensions: " + 
                                              renderWidth + "x" + renderHeight + " -> " + 
                                              displayWidth + "x" + displayHeight);
            }
            
            // Input framebuffer (low resolution)
            inputFramebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, inputFramebuffer);
            
            inputTexture = createTexture(renderWidth, renderHeight, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT);
            if (inputTexture == 0) {
                throw new RuntimeException("Failed to create input texture");
            }
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, inputTexture, 0);
            
            depthTexture = createTexture(renderWidth, renderHeight, GL30.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
            if (depthTexture == 0) {
                throw new RuntimeException("Failed to create depth texture");
            }
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
            
            checkFramebufferStatus("Input framebuffer");
            
            // Upscaled framebuffer (high resolution)
            upscaledFramebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, upscaledFramebuffer);
            
            upscaledTexture = createTexture(displayWidth, displayHeight, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT);
            if (upscaledTexture == 0) {
                throw new RuntimeException("Failed to create upscaled texture");
            }
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, upscaledTexture, 0);
            
            checkFramebufferStatus("Upscaled framebuffer");
            
            // Output framebuffer (final result)
            outputFramebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFramebuffer);
            
            outputTexture = createTexture(displayWidth, displayHeight, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT);
            if (outputTexture == 0) {
                throw new RuntimeException("Failed to create output texture");
            }
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outputTexture, 0);
            
            checkFramebufferStatus("Output framebuffer");
            
            // Reset to default framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        } catch (Exception e) {
            AMDium.LOGGER.error("Error creating framebuffers", e);
            // Clean up any resources that were created
            cleanup();
            // Reset to default framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            throw e;
        }
    }
    
    private void checkFramebufferStatus(String framebufferName) {
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            String errorMsg = "Framebuffer incomplete: " + framebufferName + ", status: " + getFramebufferStatusString(status);
            AMDium.LOGGER.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }
    
    private String getFramebufferStatusString(int status) {
        switch (status) {
            case GL30.GL_FRAMEBUFFER_UNDEFINED: return "GL_FRAMEBUFFER_UNDEFINED";
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT: return "GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT";
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT: return "GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT";
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER: return "GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER";
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER: return "GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER";
            case GL30.GL_FRAMEBUFFER_UNSUPPORTED: return "GL_FRAMEBUFFER_UNSUPPORTED";
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE: return "GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE";
            default: return "Unknown status: " + status;
        }
    }
    
    private void compileShaders() throws IOException {
        // Load only the basic FSR 1.0 shader with enhanced error handling
        try {
            // Load basic FSR 1.0 shader (combined upscaling and sharpening)
            fsr1ShaderProgram = createShaderProgram("/assets/amdium/shaders/fsr1.vert", "/assets/amdium/shaders/fsr1.frag");
            AMDium.LOGGER.info("FSR 1.0 basic shader compiled successfully");
            
            shadersCompiled = true;
        } catch (Exception e) {
            AMDium.LOGGER.error("Failed to compile FSR 1.0 shader", e);
            throw new IOException("Failed to compile FSR shader", e);
        }
    }
    
    private int createShaderProgram(String vertexPath, String fragmentPath) throws IOException {
        int vertexShader = 0;
        int fragmentShader = 0;
        int program = 0;
        
        try {
            // Load and compile vertex shader
            vertexShader = loadShader(vertexPath, GL20.GL_VERTEX_SHADER);
            
            // Load and compile fragment shader
            fragmentShader = loadShader(fragmentPath, GL20.GL_FRAGMENT_SHADER);
            
            // Create and link program
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertexShader);
            GL20.glAttachShader(program, fragmentShader);
            GL20.glLinkProgram(program);
            
            // Check for linking errors
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(program);
                throw new RuntimeException("Failed to link shader program: " + log);
            }
            
            // Validate program
            GL20.glValidateProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_VALIDATE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(program);
                AMDium.LOGGER.warn("Shader program validation warning: " + log);
                // Continue despite validation warning, as it might still work
            }
            
            return program;
        } catch (Exception e) {
            // Clean up resources on error
            if (vertexShader > 0) {
                GL20.glDeleteShader(vertexShader);
            }
            if (fragmentShader > 0) {
                GL20.glDeleteShader(fragmentShader);
            }
            if (program > 0) {
                GL20.glDeleteProgram(program);
            }
            
            throw new IOException("Failed to create shader program: " + e.getMessage(), e);
        } finally {
            // Always delete shaders after linking
            if (vertexShader > 0) {
                GL20.glDeleteShader(vertexShader);
            }
            if (fragmentShader > 0) {
                GL20.glDeleteShader(fragmentShader);
            }
        }
    }
    
    private int loadShader(String path, int type) throws IOException {
        String source;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Shader file not found: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                source = reader.lines().collect(Collectors.joining("\n"));
            }
        }
        
        int shader = GL20.glCreateShader(type);
        try {
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(shader);
                throw new RuntimeException("Failed to compile shader: " + log);
            }
            
            return shader;
        } catch (Exception e) {
            GL20.glDeleteShader(shader);
            throw new IOException("Failed to compile shader: " + e.getMessage(), e);
        }
    }
    
    /**
     * Validates if a framebuffer is complete and ready for use
     * @param framebuffer The framebuffer ID to check
     * @return True if the framebuffer is valid and complete
     */
    private boolean validateFramebuffer(int framebuffer) {
        if (framebuffer <= 0) {
            AMDium.LOGGER.error("Invalid framebuffer ID: " + framebuffer);
            return false;
        }
        
        try {
            // Save current framebuffer binding
            int[] currentFbo = new int[1];
            GL11.glGetIntegerv(GL30.GL_FRAMEBUFFER_BINDING, currentFbo);
            
            // Bind and check the framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            
            // Restore previous framebuffer binding
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo[0]);
            
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                String errorMsg = "Framebuffer " + framebuffer + " is incomplete: ";
                switch (status) {
                    case GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT:
                        errorMsg += "GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT";
                        break;
                    case GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT:
                        errorMsg += "GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT";
                        break;
                    case GL30.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER:
                        errorMsg += "GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER";
                        break;
                    case GL30.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER:
                        errorMsg += "GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER";
                        break;
                    case GL30.GL_FRAMEBUFFER_UNSUPPORTED:
                        errorMsg += "GL_FRAMEBUFFER_UNSUPPORTED";
                        break;
                    default:
                        errorMsg += "Unknown error code: " + status;
                }
                AMDium.LOGGER.error(errorMsg);
                return false;
            }
            
            return true;
        } catch (Exception e) {
            AMDium.LOGGER.error("Error validating framebuffer " + framebuffer, e);
            return false;
        }
    }
    
    /**
     * Safely bind a framebuffer for reading or drawing
     * @param target GL_READ_FRAMEBUFFER or GL_DRAW_FRAMEBUFFER
     * @param framebuffer The framebuffer ID to bind
     * @return True if binding was successful
     */
    private boolean safeBindFramebuffer(int target, int framebuffer) {
        try {
            // Default framebuffer (0) is always valid
            if (framebuffer == 0) {
                GL30.glBindFramebuffer(target, 0);
                return true;
            }
            
            // For other framebuffers, validate first
            if (validateFramebuffer(framebuffer)) {
                GL30.glBindFramebuffer(target, framebuffer);
                return true;
            } else {
                AMDium.LOGGER.error("Failed to bind invalid framebuffer: " + framebuffer);
                // Bind default framebuffer as fallback
                GL30.glBindFramebuffer(target, 0);
                return false;
            }
        } catch (Exception e) {
            AMDium.LOGGER.error("Error binding framebuffer " + framebuffer, e);
            // Bind default framebuffer as fallback
            GL30.glBindFramebuffer(target, 0);
            return false;
        }
    }
    
    /**
     * Safely perform a framebuffer blit operation with validation
     */
    private void safeBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, 
                                    int dstX0, int dstY0, int dstX1, int dstY1,
                                    int mask, int filter) {
        try {
            // Validate source dimensions
            if (srcX0 < 0 || srcY0 < 0 || srcX1 <= srcX0 || srcY1 <= srcY0) {
                AMDium.LOGGER.error("Invalid source dimensions for blit: " + 
                                   srcX0 + "," + srcY0 + " -> " + srcX1 + "," + srcY1);
                return;
            }
            
            // Validate destination dimensions
            if (dstX0 < 0 || dstY0 < 0 || dstX1 <= dstX0 || dstY1 <= dstY0) {
                AMDium.LOGGER.error("Invalid destination dimensions for blit: " + 
                                   dstX0 + "," + dstY0 + " -> " + dstX1 + "," + dstY1);
                return;
            }
            
            // Perform the blit
            GL30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in blitFramebuffer", e);
        }
    }
    
    public void processFrame(int sourceFramebuffer, long currentTime) {
        if (!initialized || !AMDium.getInstance().isFSREnabled()) return;
        
        try {
            AMDiumConfig config = AMDium.getInstance().getConfig();
            FSRType fsrType = config.getFsrType();
            
            // Update sharpness from config
            sharpness = config.getSharpness();
            
            // Validate source framebuffer
            if (sourceFramebuffer <= 0) {
                AMDium.LOGGER.error("Invalid source framebuffer: " + sourceFramebuffer);
                directRender(0); // Use default framebuffer as source
                return;
            }
            
            // Make sure dimensions are valid
            if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
                AMDium.LOGGER.error("Invalid render dimensions: " + renderWidth + "x" + renderHeight + 
                                   " -> " + displayWidth + "x" + displayHeight);
                directRender(sourceFramebuffer);
                return;
            }
            
            // Bind input framebuffer and copy from source
            if (!safeBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebuffer)) {
                directRender(0);
                return;
            }
            
            if (!safeBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, inputFramebuffer)) {
                directRender(sourceFramebuffer);
                return;
            }
            
            // Use a direct blit operation with proper dimensions
            safeBlitFramebuffer(
                0, 0, renderWidth, renderHeight,
                0, 0, renderWidth, renderHeight,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST
            );
            
            // Process with enhanced FSR 1.0
            boolean success = false;
            try {
                processFSR1Enhanced();
                success = true;
                consecutiveErrors = 0; // Reset error counter on success
            } catch (Exception e) {
                consecutiveErrors++;
                AMDium.LOGGER.error("Error processing with FSR 1.0 (attempt " + consecutiveErrors + ")", e);
                
                // Reset OpenGL state before trying fallback
                GL20.glUseProgram(0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                safeBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                
                // If we've had too many consecutive errors, disable FSR temporarily
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    AMDium.LOGGER.error("Too many consecutive FSR errors, disabling FSR temporarily");
                    success = false;
                } else {
                    // Try simple fallback
                    try {
                        copyInputToOutput();
                        success = true;
                    } catch (Exception fallbackError) {
                        AMDium.LOGGER.error("Simple fallback failed", fallbackError);
                        success = false;
                    }
                }
            }
            
            if (!success) {
                // If all FSR processing failed, use direct rendering
                directRender(sourceFramebuffer);
            }
            
            // Reset OpenGL state
            GL20.glUseProgram(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            
        } catch (Exception e) {
            // Unhandled exception - make sure we bind the default framebuffer to prevent black screen
            AMDium.LOGGER.error("Unhandled exception in processFrame", e);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            
            // Report error to AMDium
            AMDium.getInstance().reportError();
        }
    }
    
    private void directRender(int sourceFramebuffer) {
        try {
            // If source is 0, we're already on the default framebuffer
            if (sourceFramebuffer > 0) {
                // Copy directly from source to default framebuffer
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
                
                // Clear the default framebuffer
                GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                
                safeBlitFramebuffer(
                    0, 0, renderWidth, renderHeight,
                    0, 0, displayWidth, displayHeight,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR
                );
            }
            AMDium.LOGGER.info("Used direct rendering fallback");
        } catch (Exception e) {
            AMDium.LOGGER.error("Failed in direct rendering fallback", e);
            // Make absolutely sure we're on the default framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }
    
    private void copyInputToOutput() {
        // Emergency fallback - just copy the input to the screen
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, inputFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL30.glBlitFramebuffer(0, 0, renderWidth, renderHeight, 0, 0, displayWidth, displayHeight, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } catch (Exception e) {
            AMDium.LOGGER.error("Failed to copy input to output as last resort", e);
        }
    }
    
    /**
     * Enhanced FSR 1.0 implementation - simplified to avoid OpenGL errors
     */
    private void processFSR1Enhanced() {
        try {
            // Simple FSR 1.0 implementation (basic upscaling)
            if (!safeBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFramebuffer)) {
                throw new RuntimeException("Failed to bind output framebuffer");
            }
            
            // Clear the framebuffer to prevent artifacts
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            
            // Set viewport to match output dimensions
            GL11.glViewport(0, 0, displayWidth, displayHeight);
            
            // Use the FSR 1.0 shader program
            GL20.glUseProgram(fsr1ShaderProgram);
            
            // Set uniforms
            int inputSizeLoc = GL20.glGetUniformLocation(fsr1ShaderProgram, "inputSize");
            int outputSizeLoc = GL20.glGetUniformLocation(fsr1ShaderProgram, "outputSize");
            int inputTexLoc = GL20.glGetUniformLocation(fsr1ShaderProgram, "inputTexture");
            int sharpnessLoc = GL20.glGetUniformLocation(fsr1ShaderProgram, "sharpness");
        
        if (inputSizeLoc != -1) {
            GL20.glUniform2f(inputSizeLoc, renderWidth, renderHeight);
        }
        
        if (outputSizeLoc != -1) {
            GL20.glUniform2f(outputSizeLoc, displayWidth, displayHeight);
        }
            
            if (sharpnessLoc != -1) {
                GL20.glUniform1f(sharpnessLoc, sharpness);
            }
        
        // Bind input texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, inputTexture);
        
        if (inputTexLoc != -1) {
            GL20.glUniform1i(inputTexLoc, 0);
        }
        
            // Render the fullscreen quad
            try {
                // Use the static quad if available
                if (!quadInitialized) {
                    initializeQuad();
                }
                
                if (quadInitialized && quadVAO > 0) {
                    // Bind the VAO and draw
                    GL30.glBindVertexArray(quadVAO);
                    GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                    GL30.glBindVertexArray(0);
                } else {
                    // Fallback to immediate mode rendering
                    GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
                    GL11.glTexCoord2f(0.0f, 0.0f); GL11.glVertex3f(-1.0f, -1.0f, 0.0f);
                    GL11.glTexCoord2f(1.0f, 0.0f); GL11.glVertex3f(1.0f, -1.0f, 0.0f);
                    GL11.glTexCoord2f(0.0f, 1.0f); GL11.glVertex3f(-1.0f, 1.0f, 0.0f);
                    GL11.glTexCoord2f(1.0f, 1.0f); GL11.glVertex3f(1.0f, 1.0f, 0.0f);
                    GL11.glEnd();
                }
            } catch (Exception e) {
                AMDium.LOGGER.error("Error rendering quad, using fallback", e);
                // Ultimate fallback
                GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
                GL11.glTexCoord2f(0.0f, 0.0f); GL11.glVertex3f(-1.0f, -1.0f, 0.0f);
                GL11.glTexCoord2f(1.0f, 0.0f); GL11.glVertex3f(1.0f, -1.0f, 0.0f);
                GL11.glTexCoord2f(0.0f, 1.0f); GL11.glVertex3f(-1.0f, 1.0f, 0.0f);
                GL11.glTexCoord2f(1.0f, 1.0f); GL11.glVertex3f(1.0f, 1.0f, 0.0f);
                GL11.glEnd();
        }
        
        // Copy to default framebuffer
        if (!safeBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outputFramebuffer)) {
            throw new RuntimeException("Failed to bind output framebuffer for reading");
        }
        
        if (!safeBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0)) {
            throw new RuntimeException("Failed to bind default framebuffer for drawing");
        }
        
        // Clear the default framebuffer
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        
        // Blit the output to the default framebuffer
        safeBlitFramebuffer(
            0, 0, displayWidth, displayHeight,
            0, 0, displayWidth, displayHeight,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
        );
        
        // Reset state
        GL20.glUseProgram(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        
        // Log success for debugging
        AMDium.LOGGER.debug("Enhanced FSR1 processing completed successfully");
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in processFSR1Enhanced", e);
            throw e; // Rethrow to be handled by the caller
        }
    }
    
    /**
     * Simple FSR 1.0 implementation (basic upscaling) - kept as a fallback
     */
    private void processFSR1() {
        try {
            // Simple FSR 1.0 implementation (basic upscaling)
            if (!safeBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFramebuffer)) {
                directRender(0);
                return;
            }
            
            // Clear the framebuffer to prevent artifacts
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            
            // Set viewport to match output dimensions
            GL11.glViewport(0, 0, displayWidth, displayHeight);
            
            // Use the FSR 1.0 shader program
            GL20.glUseProgram(fsr1ShaderProgram);
            
            // Set uniforms
            int inputSizeLoc = GL20.glGetUniformLocation(fsr1ShaderProgram, "inputSize");
            int outputSizeLoc = GL20.glGetUniformLocation(fsr1ShaderProgram, "outputSize");
            int inputTexLoc = GL20.glGetUniformLocation(fsr1ShaderProgram, "inputTexture");
            int sharpnessLoc = GL20.glGetUniformLocation(fsr1ShaderProgram, "sharpness");
            
            if (inputSizeLoc != -1) {
                GL20.glUniform2f(inputSizeLoc, renderWidth, renderHeight);
            }
            
            if (outputSizeLoc != -1) {
                GL20.glUniform2f(outputSizeLoc, displayWidth, displayHeight);
            }
            
            if (sharpnessLoc != -1) {
                GL20.glUniform1f(sharpnessLoc, sharpness);
            }
            
            // Bind input texture
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, inputTexture);
            
            if (inputTexLoc != -1) {
                GL20.glUniform1i(inputTexLoc, 0);
            }
            
            // Render the fullscreen quad
            renderFullscreenQuad();
            
            // Copy to default framebuffer
            if (!safeBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outputFramebuffer)) {
                directRender(0);
                return;
            }
            
            if (!safeBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0)) {
                return; // This should never fail as 0 is the default framebuffer
            }
            
            // Clear the default framebuffer
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            
            // Blit the output to the default framebuffer
            safeBlitFramebuffer(
                0, 0, displayWidth, displayHeight,
                0, 0, displayWidth, displayHeight,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
            );
            
            // Reset state
            GL20.glUseProgram(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            
            // Log success for debugging
            AMDium.LOGGER.debug("FSR1 processing completed successfully");
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in processFSR1", e);
            // Reset to default framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            // Try direct rendering as a fallback
            directRender(0);
        }
    }
    
    /**
     * Initialize the static fullscreen quad
     */
    private static void initializeQuad() {
        if (quadInitialized) return;
        
        try {
            // Make sure we're not in the middle of rendering
            GL30.glFinish();
            
            // Create a VAO for the fullscreen quad
            quadVAO = GL30.glGenVertexArrays();
            if (quadVAO <= 0) {
                AMDium.LOGGER.error("Failed to create VAO for fullscreen quad");
                return;
            }
            
            GL30.glBindVertexArray(quadVAO);
            
            // Create a fullscreen quad for rendering
            float[] vertices = {
                -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
                1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
                -1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                1.0f, 1.0f, 0.0f, 1.0f, 1.0f
            };
            
            quadVBO = GL15.glGenBuffers();
            if (quadVBO <= 0) {
                AMDium.LOGGER.error("Failed to create VBO for fullscreen quad");
                GL30.glDeleteVertexArrays(quadVAO);
                quadVAO = -1;
                return;
            }
            
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVBO);
            
            FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(vertices.length);
            vertexBuffer.put(vertices).flip();
            
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
            MemoryUtil.memFree(vertexBuffer);
            
            // Position attribute
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 5 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);
            
            // Texture coordinate attribute
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            
            // Unbind VAO
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            
            quadInitialized = true;
            AMDium.LOGGER.info("Static fullscreen quad initialized");
        } catch (Exception e) {
            AMDium.LOGGER.error("Failed to initialize static fullscreen quad", e);
            // Clean up any resources that were created
            if (quadVBO > 0) {
                GL15.glDeleteBuffers(quadVBO);
                quadVBO = -1;
            }
            if (quadVAO > 0) {
                GL30.glDeleteVertexArrays(quadVAO);
                quadVAO = -1;
            }
            quadInitialized = false;
        }
    }
    
    /**
     * Clean up the static fullscreen quad
     */
    public static void cleanupQuad() {
        if (!quadInitialized) return;
        
        try {
            // Make sure we're not in the middle of rendering
            GL30.glFinish();
            
            if (quadVBO > 0) {
                GL15.glDeleteBuffers(quadVBO);
                quadVBO = -1;
            }
            if (quadVAO > 0) {
                GL30.glDeleteVertexArrays(quadVAO);
                quadVAO = -1;
            }
            
            quadInitialized = false;
            AMDium.LOGGER.info("Static fullscreen quad cleaned up");
        } catch (Exception e) {
            AMDium.LOGGER.error("Error cleaning up static fullscreen quad", e);
        }
    }
    
    private void renderFullscreenQuad() {
        try {
            // Use the static quad if available
            if (!quadInitialized) {
                initializeQuad();
            }
            
            if (quadInitialized && quadVAO > 0) {
                // Bind the VAO and draw
                GL30.glBindVertexArray(quadVAO);
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                GL30.glBindVertexArray(0);
            } else {
                // Fallback to creating a temporary quad
                renderTemporaryQuad();
            }
        } catch (Exception e) {
            AMDium.LOGGER.error("Error rendering fullscreen quad", e);
            // Try the fallback method
            try {
                renderTemporaryQuad();
            } catch (Exception fallbackError) {
                AMDium.LOGGER.error("Critical error: Failed to render even with fallback method", fallbackError);
            }
        }
    }
    
    private void renderTemporaryQuad() {
        int vao = 0;
        int vbo = 0;
        
        try {
            // Create a temporary VAO and VBO
            vao = GL30.glGenVertexArrays();
            if (vao <= 0) {
                AMDium.LOGGER.error("Failed to create temporary VAO");
                return;
            }
            
            GL30.glBindVertexArray(vao);
            
            // Create a fullscreen quad for rendering
            float[] vertices = {
                -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
                1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
                -1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                1.0f, 1.0f, 0.0f, 1.0f, 1.0f
            };
            
            vbo = GL15.glGenBuffers();
            if (vbo <= 0) {
                AMDium.LOGGER.error("Failed to create temporary VBO");
                GL30.glDeleteVertexArrays(vao);
                return;
            }
            
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            
            FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(vertices.length);
            vertexBuffer.put(vertices).flip();
            
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STREAM_DRAW);
            MemoryUtil.memFree(vertexBuffer);
            
            // Position attribute
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 5 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);
            
            // Texture coordinate attribute
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            
            // Draw the quad
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in renderTemporaryQuad", e);
        } finally {
            // Clean up
            if (vao > 0) {
                GL20.glDisableVertexAttribArray(0);
                GL20.glDisableVertexAttribArray(1);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                GL30.glBindVertexArray(0);
                GL30.glDeleteVertexArrays(vao);
            }
            
            if (vbo > 0) {
                GL15.glDeleteBuffers(vbo);
            }
        }
    }
    
    public int getInputFramebuffer() {
        return inputFramebuffer;
    }
    
    public int getRenderWidth() {
        return renderWidth;
    }
    
    public int getRenderHeight() {
        return renderHeight;
    }
    
    /**
     * Verify that all framebuffers are valid
     */
    private boolean verifyFramebuffers() {
        try {
            // Check that all framebuffer IDs are valid
            if (inputFramebuffer <= 0 || upscaledFramebuffer <= 0 || 
                outputFramebuffer <= 0 || depthTexture <= 0) {
                AMDium.LOGGER.error("Invalid framebuffer IDs");
                return false;
            }
            
            // Check that all texture IDs are valid
            if (inputTexture <= 0 || upscaledTexture <= 0 || 
                outputTexture <= 0 || depthTexture <= 0) {
                AMDium.LOGGER.error("Invalid texture IDs");
                return false;
            }
            
            // Check framebuffer completeness for each framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, inputFramebuffer);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                AMDium.LOGGER.error("Input framebuffer is incomplete");
                return false;
            }
            
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, upscaledFramebuffer);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                AMDium.LOGGER.error("Upscaled framebuffer is incomplete");
                return false;
            }
            
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFramebuffer);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                AMDium.LOGGER.error("Output framebuffer is incomplete");
                return false;
            }
            
            // Reset to default framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            return true;
        } catch (Exception e) {
            AMDium.LOGGER.error("Error verifying framebuffers", e);
            return false;
        }
    }
} 