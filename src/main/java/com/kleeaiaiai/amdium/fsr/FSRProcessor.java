package com.kleeaiaiai.amdium.fsr;

import com.kleeaiaiai.amdium.AMDium;
import com.kleeaiaiai.amdium.config.AMDiumConfig;
import org.lwjgl.opengl.GL11;
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
    
    // Shader programs for different FSR versions
    private int fsr1ShaderProgram;
    private int fsr2ShaderProgram;
    private int upscaleShaderProgram;
    private int rcasShaderProgram;
    private int frameGenShaderProgram;
    
    private int inputFramebuffer;
    private int upscaledFramebuffer;
    private int outputFramebuffer;
    private int historyFramebuffer;
    private int motionVectorFramebuffer;
    
    private int inputTexture;
    private int upscaledTexture;
    private int outputTexture;
    private int historyTexture;
    private int motionVectorTexture;
    private int depthTexture;
    
    private int displayWidth;
    private int displayHeight;
    private int renderWidth;
    private int renderHeight;
    
    private long lastFrameTime;
    private float frameTime;
    
    private boolean initialized = false;
    private boolean shadersCompiled = false;
    
    public void initialize() {
        try {
            // Set default dimensions to avoid null pointer exceptions
            if (displayWidth == 0 || displayHeight == 0) {
                // Get the current Minecraft window size if possible
                try {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null && client.getWindow() != null) {
                        displayWidth = client.getWindow().getFramebufferWidth();
                        displayHeight = client.getWindow().getFramebufferHeight();
                    } else {
                        // Fallback to default values
                        displayWidth = 1920;  // Default width
                        displayHeight = 1080; // Default height
                    }
                } catch (Exception e) {
                    // Fallback to default values
                    displayWidth = 1920;  // Default width
                    displayHeight = 1080; // Default height
                    AMDium.LOGGER.warn("Could not get window size, using defaults", e);
                }
                
                AMDiumConfig config = AMDium.getInstance().getConfig();
                FSRQualityMode qualityMode = config.getQualityMode();
                renderWidth = qualityMode.calculateRenderWidth(displayWidth);
                renderHeight = qualityMode.calculateRenderHeight(displayHeight);
                
                AMDium.LOGGER.info("Initialized FSR with dimensions: " + renderWidth + "x" + renderHeight + 
                                  " -> " + displayWidth + "x" + displayHeight);
            }
            
            if (!shadersCompiled) {
                compileShaders();
                shadersCompiled = true;
            }
            
            // Create framebuffers with the current dimensions
            createFramebuffers();
            
            // Verify that the framebuffers were created successfully
            boolean framebuffersValid = verifyFramebuffers();
            if (!framebuffersValid) {
                throw new RuntimeException("Failed to create valid framebuffers");
            }
            
            initialized = true;
            AMDium.LOGGER.info("FSR processor initialized with dimensions: " + renderWidth + "x" + renderHeight + 
                              " -> " + displayWidth + "x" + displayHeight);
        } catch (Exception e) {
            AMDium.LOGGER.error("Failed to initialize FSR processor", e);
            // Reset to default framebuffer to prevent black screen
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            throw new RuntimeException("FSR initialization failed", e);
        }
    }
    
    public void cleanup() {
        if (!initialized) return;
        
        GL20.glDeleteProgram(fsr1ShaderProgram);
        GL20.glDeleteProgram(fsr2ShaderProgram);
        GL20.glDeleteProgram(upscaleShaderProgram);
        GL20.glDeleteProgram(rcasShaderProgram);
        GL20.glDeleteProgram(frameGenShaderProgram);
        
        GL30.glDeleteFramebuffers(inputFramebuffer);
        GL30.glDeleteFramebuffers(upscaledFramebuffer);
        GL30.glDeleteFramebuffers(outputFramebuffer);
        GL30.glDeleteFramebuffers(historyFramebuffer);
        GL30.glDeleteFramebuffers(motionVectorFramebuffer);
        
        GL11.glDeleteTextures(inputTexture);
        GL11.glDeleteTextures(upscaledTexture);
        GL11.glDeleteTextures(outputTexture);
        GL11.glDeleteTextures(historyTexture);
        GL11.glDeleteTextures(motionVectorTexture);
        GL11.glDeleteTextures(depthTexture);
        
        initialized = false;
        // Don't reset shadersCompiled flag - we don't need to recompile shaders
    }
    
    public void resizeBuffers(int width, int height) {
        if (!initialized) return;
        
        AMDiumConfig config = AMDium.getInstance().getConfig();
        FSRQualityMode qualityMode = config.getQualityMode();
        
        displayWidth = width;
        displayHeight = height;
        renderWidth = qualityMode.calculateRenderWidth(width);
        renderHeight = qualityMode.calculateRenderHeight(height);
        
        // Delete old framebuffers
        GL30.glDeleteFramebuffers(inputFramebuffer);
        GL30.glDeleteFramebuffers(upscaledFramebuffer);
        GL30.glDeleteFramebuffers(outputFramebuffer);
        GL30.glDeleteFramebuffers(historyFramebuffer);
        GL30.glDeleteFramebuffers(motionVectorFramebuffer);
        
        GL11.glDeleteTextures(inputTexture);
        GL11.glDeleteTextures(upscaledTexture);
        GL11.glDeleteTextures(outputTexture);
        GL11.glDeleteTextures(historyTexture);
        GL11.glDeleteTextures(motionVectorTexture);
        GL11.glDeleteTextures(depthTexture);
        
        // Create new framebuffers with the new size
        createFramebuffers();
        
        // No need to recompile shaders here - they don't depend on size
        AMDium.LOGGER.info("FSR buffers resized to " + renderWidth + "x" + renderHeight + " -> " + displayWidth + "x" + displayHeight);
    }
    
    private void createFramebuffers() {
        // Input framebuffer (low resolution)
        inputFramebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, inputFramebuffer);
        
        inputTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, inputTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, renderWidth, renderHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, inputTexture, 0);
        
        depthTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24, renderWidth, renderHeight, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
        
        checkFramebufferStatus("Input framebuffer");
        
        // Upscaled framebuffer (high resolution)
        upscaledFramebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, upscaledFramebuffer);
        
        upscaledTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, upscaledTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, displayWidth, displayHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, upscaledTexture, 0);
        
        checkFramebufferStatus("Upscaled framebuffer");
        
        // Output framebuffer (final result)
        outputFramebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFramebuffer);
        
        outputTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, outputTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, displayWidth, displayHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outputTexture, 0);
        
        checkFramebufferStatus("Output framebuffer");
        
        // History framebuffer (for frame generation)
        historyFramebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, historyFramebuffer);
        
        historyTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, historyTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, displayWidth, displayHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, historyTexture, 0);
        
        checkFramebufferStatus("History framebuffer");
        
        // Motion vector framebuffer
        motionVectorFramebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, motionVectorFramebuffer);
        
        motionVectorTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, motionVectorTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, renderWidth, renderHeight, 0, GL30.GL_RG, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, motionVectorTexture, 0);
        
        checkFramebufferStatus("Motion vector framebuffer");
        
        // Reset to default framebuffer
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
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
        // Prioritize loading FSR 1.0 shaders first since it's the default
        fsr1ShaderProgram = createShaderProgram("/assets/amdium/shaders/fsr1.vert", "/assets/amdium/shaders/fsr1.frag");
        AMDium.LOGGER.info("FSR 1.0 shaders compiled successfully");
        
        // Load other shaders
        try {
            fsr2ShaderProgram = createShaderProgram("/assets/amdium/shaders/fsr2.vert", "/assets/amdium/shaders/fsr2.frag");
            upscaleShaderProgram = createShaderProgram("/assets/amdium/shaders/fsr_easu.vert", "/assets/amdium/shaders/fsr_easu.frag");
            rcasShaderProgram = createShaderProgram("/assets/amdium/shaders/fsr_rcas.vert", "/assets/amdium/shaders/fsr_rcas.frag");
            frameGenShaderProgram = createShaderProgram("/assets/amdium/shaders/fsr_framegen.vert", "/assets/amdium/shaders/fsr_framegen.frag");
            AMDium.LOGGER.info("All FSR shaders compiled successfully");
        } catch (Exception e) {
            AMDium.LOGGER.warn("Some advanced FSR shaders failed to compile, falling back to FSR 1.0", e);
            // If advanced shaders fail, we can still use FSR 1.0
        }
    }
    
    private int createShaderProgram(String vertexPath, String fragmentPath) throws IOException {
        int vertexShader = loadShader(vertexPath, GL20.GL_VERTEX_SHADER);
        int fragmentShader = loadShader(fragmentPath, GL20.GL_FRAGMENT_SHADER);
        
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glLinkProgram(program);
        
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            throw new RuntimeException("Failed to link shader program: " + log);
        }
        
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        
        return program;
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
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            throw new RuntimeException("Failed to compile shader: " + log);
        }
        
        return shader;
    }
    
    public void processFrame(int sourceFramebuffer, long currentTime) {
        if (!initialized || !AMDium.getInstance().isFSREnabled()) return;
        
        try {
            AMDiumConfig config = AMDium.getInstance().getConfig();
            FSRType fsrType = config.getFsrType();
            
            // Calculate frame time for motion estimation
            if (lastFrameTime == 0) {
                lastFrameTime = currentTime;
                frameTime = 0.016f; // Default to 60 FPS
            } else {
                frameTime = (currentTime - lastFrameTime) / 1000.0f;
                lastFrameTime = currentTime;
            }
            
            // Bind input framebuffer and copy from source
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, inputFramebuffer);
            
            // Check if source framebuffer is valid (sourceFramebuffer > 0)
            if (sourceFramebuffer > 0) {
                // Use a direct blit operation with proper dimensions
                GL30.glBlitFramebuffer(
                    0, 0, renderWidth, renderHeight,
                    0, 0, renderWidth, renderHeight,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST
                );
                
                // Process based on FSR type
                boolean success = false;
                try {
                    switch (fsrType) {
                        case FSR_1:
                            processFSR1();
                            success = true;
                            break;
                        case FSR_2:
                            processFSR2();
                            success = true;
                            break;
                        case FSR_3:
                            processFSR3(config);
                            success = true;
                            break;
                        default:
                            // Default to FSR 1.0 if something goes wrong
                            processFSR1();
                            success = true;
                            break;
                    }
                } catch (Exception e) {
                    AMDium.LOGGER.error("Error processing frame with " + fsrType + ", falling back to FSR 1.0", e);
                    try {
                        processFSR1();
                        success = true;
                    } catch (Exception fallbackError) {
                        AMDium.LOGGER.error("Critical error in FSR processing", fallbackError);
                        // If even FSR 1.0 fails, copy the input directly to the output
                        success = false;
                    }
                }
                
                // If all FSR methods failed, use direct rendering as a last resort
                if (!success) {
                    directRender(sourceFramebuffer);
                }
            } else {
                AMDium.LOGGER.error("Invalid source framebuffer: " + sourceFramebuffer);
                // Use direct rendering as a fallback
                directRender(0); // Use default framebuffer as source
            }
        } catch (Exception e) {
            AMDium.LOGGER.error("Unhandled exception in FSR processing", e);
            // Reset to default framebuffer to prevent black screen
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            // Try direct rendering as a last resort
            try {
                directRender(0);
            } catch (Exception ex) {
                // At this point, we've tried everything - just make sure we're on the default framebuffer
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            }
        }
    }
    
    /**
     * Direct rendering fallback that bypasses FSR completely
     * This is used as a last resort when FSR processing fails
     */
    private void directRender(int sourceFramebuffer) {
        try {
            // If source is 0, we're already on the default framebuffer
            if (sourceFramebuffer > 0) {
                // Copy directly from source to default framebuffer
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
                GL30.glBlitFramebuffer(
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
    
    private void processFSR1() {
        try {
            // Simple FSR 1.0 implementation (basic upscaling)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFramebuffer);
            
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
            
            if (inputSizeLoc != -1) {
                GL20.glUniform2f(inputSizeLoc, renderWidth, renderHeight);
            }
            
            if (outputSizeLoc != -1) {
                GL20.glUniform2f(outputSizeLoc, displayWidth, displayHeight);
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
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outputFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            
            // Clear the default framebuffer
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            
            // Blit the output to the default framebuffer
            GL30.glBlitFramebuffer(
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
            throw e;
        }
    }
    
    private void processFSR2() {
        // FSR 2.0 implementation (temporal upscaling)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, upscaledFramebuffer);
        GL11.glViewport(0, 0, displayWidth, displayHeight);
        
        GL20.glUseProgram(fsr2ShaderProgram);
        
        GL20.glUniform2f(GL20.glGetUniformLocation(fsr2ShaderProgram, "inputSize"), renderWidth, renderHeight);
        GL20.glUniform2f(GL20.glGetUniformLocation(fsr2ShaderProgram, "outputSize"), displayWidth, displayHeight);
        GL20.glUniform1f(GL20.glGetUniformLocation(fsr2ShaderProgram, "frameTime"), frameTime);
        
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, inputTexture);
        GL20.glUniform1i(GL20.glGetUniformLocation(fsr2ShaderProgram, "inputTexture"), 0);
        
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, historyTexture);
        GL20.glUniform1i(GL20.glGetUniformLocation(fsr2ShaderProgram, "historyTexture"), 1);
        
        renderFullscreenQuad();
        
        // Copy current frame to history buffer for next frame
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, upscaledFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, historyFramebuffer);
        GL30.glBlitFramebuffer(0, 0, displayWidth, displayHeight, 0, 0, displayWidth, displayHeight, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        
        // Copy to default framebuffer
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, upscaledFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        GL30.glBlitFramebuffer(0, 0, displayWidth, displayHeight, 0, 0, displayWidth, displayHeight, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
    }
    
    private void processFSR3(AMDiumConfig config) {
        // Step 1: EASU (Edge Adaptive Spatial Upsampling)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, upscaledFramebuffer);
        GL11.glViewport(0, 0, displayWidth, displayHeight);
        
        GL20.glUseProgram(upscaleShaderProgram);
        
        GL20.glUniform2f(GL20.glGetUniformLocation(upscaleShaderProgram, "inputSize"), renderWidth, renderHeight);
        GL20.glUniform2f(GL20.glGetUniformLocation(upscaleShaderProgram, "outputSize"), displayWidth, displayHeight);
        
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, inputTexture);
        GL20.glUniform1i(GL20.glGetUniformLocation(upscaleShaderProgram, "inputTexture"), 0);
        
        renderFullscreenQuad();
        
        // Step 2: RCAS (Robust Contrast Adaptive Sharpening)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFramebuffer);
        
        GL20.glUseProgram(rcasShaderProgram);
        
        GL20.glUniform1f(GL20.glGetUniformLocation(rcasShaderProgram, "sharpness"), config.getSharpness());
        
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, upscaledTexture);
        GL20.glUniform1i(GL20.glGetUniformLocation(rcasShaderProgram, "inputTexture"), 0);
        
        renderFullscreenQuad();
        
        // Step 3: Frame Generation (if enabled)
        if (config.isFrameGeneration()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0); // Final output
            
            GL20.glUseProgram(frameGenShaderProgram);
            
            GL20.glUniform1f(GL20.glGetUniformLocation(frameGenShaderProgram, "frameTime"), frameTime);
            GL20.glUniform1i(GL20.glGetUniformLocation(frameGenShaderProgram, "strength"), config.getFrameGenerationStrength());
            
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, outputTexture);
            GL20.glUniform1i(GL20.glGetUniformLocation(frameGenShaderProgram, "currentFrame"), 0);
            
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, historyTexture);
            GL20.glUniform1i(GL20.glGetUniformLocation(frameGenShaderProgram, "previousFrame"), 1);
            
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, motionVectorTexture);
            GL20.glUniform1i(GL20.glGetUniformLocation(frameGenShaderProgram, "motionVectors"), 2);
            
            renderFullscreenQuad();
            
            // Copy current frame to history buffer for next frame
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outputFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, historyFramebuffer);
            GL30.glBlitFramebuffer(0, 0, displayWidth, displayHeight, 0, 0, displayWidth, displayHeight, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } else {
            // If frame generation is disabled, just copy the output to the default framebuffer
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outputFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL30.glBlitFramebuffer(0, 0, displayWidth, displayHeight, 0, 0, displayWidth, displayHeight, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }
    }
    
    /**
     * Initialize the static fullscreen quad
     */
    private static void initializeQuad() {
        if (quadInitialized) return;
        
        try {
            // Create a VAO for the fullscreen quad
            quadVAO = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(quadVAO);
            
            // Create a fullscreen quad for rendering
            float[] vertices = {
                -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
                1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
                -1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                1.0f, 1.0f, 0.0f, 1.0f, 1.0f
            };
            
            quadVBO = GL15.glGenBuffers();
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
    }
    
    private void renderFullscreenQuad() {
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
            int vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            
            // Create a fullscreen quad for rendering
            float[] vertices = {
                -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
                1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
                -1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                1.0f, 1.0f, 0.0f, 1.0f, 1.0f
            };
            
            int vbo = GL15.glGenBuffers();
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
            
            // Clean up
            GL20.glDisableVertexAttribArray(0);
            GL20.glDisableVertexAttribArray(1);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL30.glBindVertexArray(0);
            GL15.glDeleteBuffers(vbo);
            GL30.glDeleteVertexArrays(vao);
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
                outputFramebuffer <= 0 || historyFramebuffer <= 0 || 
                motionVectorFramebuffer <= 0) {
                AMDium.LOGGER.error("Invalid framebuffer IDs");
                return false;
            }
            
            // Check that all texture IDs are valid
            if (inputTexture <= 0 || upscaledTexture <= 0 || 
                outputTexture <= 0 || historyTexture <= 0 || 
                motionVectorTexture <= 0 || depthTexture <= 0) {
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
            
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, historyFramebuffer);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                AMDium.LOGGER.error("History framebuffer is incomplete");
                return false;
            }
            
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, motionVectorFramebuffer);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                AMDium.LOGGER.error("Motion vector framebuffer is incomplete");
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