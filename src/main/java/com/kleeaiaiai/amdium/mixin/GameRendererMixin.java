package com.kleeaiaiai.amdium.mixin;

import com.kleeaiaiai.amdium.AMDium;
import com.kleeaiaiai.amdium.fsr.FSRProcessor;
import com.kleeaiaiai.amdium.fsr.FSRQualityMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow @Final private MinecraftClient client;
    
    private Framebuffer originalFramebuffer;
    private boolean isProcessingFrame = false;
    private long lastResizeTime = 0;
    private static final long RESIZE_THROTTLE_MS = 500;
    private int lastWidth = 0;
    private int lastHeight = 0;
    private int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    
    // State preservation variables
    private int previousShaderProgram = 0;
    private int previousTexture = 0;
    private int previousFBO = 0;
    
    private void saveGLState() {
        previousShaderProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        previousFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
    }
    
    private void restoreGLState() {
        GL20.glUseProgram(previousShaderProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFBO);
    }
    
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (isProcessingFrame || !AMDium.getInstance().isFSREnabled()) return;
        
        try {
            FSRProcessor fsrProcessor = AMDium.getInstance().getFSRProcessor();
            if (fsrProcessor == null) return;
            
            // Save current GL state
            saveGLState();
            
            originalFramebuffer = this.client.getFramebuffer();
            if (originalFramebuffer == null || originalFramebuffer.fbo <= 0) {
                throw new IllegalStateException("Invalid original framebuffer");
            }
            
            int width = originalFramebuffer.textureWidth;
            int height = originalFramebuffer.textureHeight;
            
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("Invalid framebuffer dimensions: " + width + "x" + height);
            }
            
            long currentTime = System.currentTimeMillis();
            FSRQualityMode qualityMode = AMDium.getInstance().getConfig().getQualityMode();
            float scaleFactor = qualityMode.getScaleFactor();
            int targetRenderWidth = Math.max(1, (int)(width / scaleFactor));
            int targetRenderHeight = Math.max(1, (int)(height / scaleFactor));
            
            boolean dimensionsChanged = (lastWidth != width || lastHeight != height);
            boolean renderDimensionsWrong = Math.abs(fsrProcessor.getRenderWidth() - targetRenderWidth) > 2 ||
                                          Math.abs(fsrProcessor.getRenderHeight() - targetRenderHeight) > 2;
            
            if ((dimensionsChanged || renderDimensionsWrong) && 
                (currentTime - lastResizeTime > RESIZE_THROTTLE_MS)) {
                
                GL11.glFinish();
                
                AMDium.LOGGER.info("Resizing FSR buffers: " + targetRenderWidth + "x" + targetRenderHeight + 
                                  " -> " + width + "x" + height);
                
                fsrProcessor.resizeBuffers(width, height);
                lastResizeTime = currentTime;
                lastWidth = width;
                lastHeight = height;
                
                consecutiveErrors = 0;
            }
            
            isProcessingFrame = true;
            
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in render start", e);
            handleRenderError();
        }
    }
    
    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (!isProcessingFrame || !AMDium.getInstance().isFSREnabled()) return;
        
        try {
            FSRProcessor fsrProcessor = AMDium.getInstance().getFSRProcessor();
            if (fsrProcessor == null) return;
            
            if (originalFramebuffer != null && originalFramebuffer.fbo > 0) {
                // Save current GL state before processing
                saveGLState();
                
                GL11.glFinish();
                fsrProcessor.processFrame(originalFramebuffer.fbo, System.currentTimeMillis());
                GL11.glFinish();
                
                // Restore GL state after processing
                restoreGLState();
                
                consecutiveErrors = 0;
            }
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in render end", e);
            handleRenderError();
        } finally {
            isProcessingFrame = false;
            originalFramebuffer = null;
            
            // Ensure proper state restoration
            restoreGLState();
        }
    }
    
    @Inject(method = "onResized", at = @At("RETURN"))
    private void onResized(int width, int height, CallbackInfo ci) {
        if (!AMDium.getInstance().isFSREnabled()) return;
        
        try {
            lastWidth = width;
            lastHeight = height;
            lastResizeTime = 0;
            
            // Save and restore GL state
            saveGLState();
            GL11.glFinish();
            restoreGLState();
            
            AMDium.LOGGER.info("Screen resized to " + width + "x" + height + ", FSR buffers will update next frame");
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in onResized", e);
            handleRenderError();
        }
    }
    
    private void handleRenderError() {
        consecutiveErrors++;
        
        // Save current state before error handling
        saveGLState();
        GL11.glFinish();
        
        // Restore state after error handling
        restoreGLState();
        
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            AMDium.LOGGER.error("Too many consecutive render errors, disabling FSR");
            AMDium.getInstance().reportError();
        }
    }
} 