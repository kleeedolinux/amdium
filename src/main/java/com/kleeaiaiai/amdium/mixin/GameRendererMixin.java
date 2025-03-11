package com.kleeaiaiai.amdium.mixin;

import com.kleeaiaiai.amdium.AMDium;
import com.kleeaiaiai.amdium.fsr.FSRProcessor;
import com.kleeaiaiai.amdium.fsr.FSRQualityMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.GameRenderer;
import org.lwjgl.opengl.GL11;
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
    private static final long RESIZE_THROTTLE_MS = 500; // Reduced to 500ms for more responsive resizing
    private int lastWidth = 0;
    private int lastHeight = 0;
    private int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (isProcessingFrame || !AMDium.getInstance().isFSREnabled()) return;
        
        try {
            FSRProcessor fsrProcessor = AMDium.getInstance().getFSRProcessor();
            if (fsrProcessor == null) return;
            
            // Store the original framebuffer and validate it
            originalFramebuffer = this.client.getFramebuffer();
            if (originalFramebuffer == null || originalFramebuffer.fbo <= 0) {
                throw new IllegalStateException("Invalid original framebuffer");
            }
            
            // Get current dimensions and validate
            int width = originalFramebuffer.textureWidth;
            int height = originalFramebuffer.textureHeight;
            
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("Invalid framebuffer dimensions: " + width + "x" + height);
            }
            
            // Calculate target dimensions
            long currentTime = System.currentTimeMillis();
            FSRQualityMode qualityMode = AMDium.getInstance().getConfig().getQualityMode();
            float scaleFactor = qualityMode.getScaleFactor();
            int targetRenderWidth = Math.max(1, (int)(width / scaleFactor));
            int targetRenderHeight = Math.max(1, (int)(height / scaleFactor));
            
            // Check if resize is needed
            boolean dimensionsChanged = (lastWidth != width || lastHeight != height);
            boolean renderDimensionsWrong = Math.abs(fsrProcessor.getRenderWidth() - targetRenderWidth) > 2 ||
                                          Math.abs(fsrProcessor.getRenderHeight() - targetRenderHeight) > 2;
            
            if ((dimensionsChanged || renderDimensionsWrong) && 
                (currentTime - lastResizeTime > RESIZE_THROTTLE_MS)) {
                
                // Ensure clean state before resize
                GL11.glFinish();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                
                AMDium.LOGGER.info("Resizing FSR buffers: " + targetRenderWidth + "x" + targetRenderHeight + 
                                  " -> " + width + "x" + height);
                
                fsrProcessor.resizeBuffers(width, height);
                lastResizeTime = currentTime;
                lastWidth = width;
                lastHeight = height;
                
                // Reset error counter after successful resize
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
            
            // Process the frame with FSR
            if (originalFramebuffer != null && originalFramebuffer.fbo > 0) {
                // Wait for any pending operations
                GL11.glFinish();
                
                // Process frame and ensure it completes
                fsrProcessor.processFrame(originalFramebuffer.fbo, System.currentTimeMillis());
                GL11.glFinish();
                
                // Reset error counter on successful frame
                consecutiveErrors = 0;
            }
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in render end", e);
            handleRenderError();
        } finally {
            // Always reset state
            isProcessingFrame = false;
            originalFramebuffer = null;
            
            // Ensure we're back to default framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }
    
    @Inject(method = "onResized", at = @At("RETURN"))
    private void onResized(int width, int height, CallbackInfo ci) {
        if (!AMDium.getInstance().isFSREnabled()) return;
        
        try {
            // Force immediate resize on next frame
            lastWidth = width;
            lastHeight = height;
            lastResizeTime = 0;
            
            // Clean up OpenGL state
            GL11.glFinish();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            
            AMDium.LOGGER.info("Screen resized to " + width + "x" + height + ", FSR buffers will update next frame");
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in onResized", e);
            handleRenderError();
        }
    }
    
    private void handleRenderError() {
        consecutiveErrors++;
        
        // Reset OpenGL state
        GL11.glFinish();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            AMDium.LOGGER.error("Too many consecutive render errors, disabling FSR");
            AMDium.getInstance().reportError();
        }
    }
} 