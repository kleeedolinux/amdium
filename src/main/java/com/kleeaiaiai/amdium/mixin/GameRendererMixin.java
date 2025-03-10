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
    private static final long RESIZE_THROTTLE_MS = 1000; // Only resize once per second at most
    private int lastWidth = 0;
    private int lastHeight = 0;
    
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (isProcessingFrame || !AMDium.getInstance().isFSREnabled()) return;
        
        try {
            FSRProcessor fsrProcessor = AMDium.getInstance().getFSRProcessor();
            if (fsrProcessor == null) return;
            
            // Store the original framebuffer
            originalFramebuffer = this.client.getFramebuffer();
            if (originalFramebuffer == null || originalFramebuffer.fbo <= 0) {
                AMDium.LOGGER.error("Invalid original framebuffer");
                return;
            }
            
            // Check if we need to resize the FSR buffers, but throttle the checks
            int width = originalFramebuffer.textureWidth;
            int height = originalFramebuffer.textureHeight;
            
            if (width <= 0 || height <= 0) {
                AMDium.LOGGER.error("Invalid framebuffer dimensions: " + width + "x" + height);
                return;
            }
            
            long currentTime = System.currentTimeMillis();
            FSRQualityMode qualityMode = AMDium.getInstance().getConfig().getQualityMode();
            float scaleFactor = qualityMode.getScaleFactor();
            int targetRenderWidth = (int)(width / scaleFactor);
            int targetRenderHeight = (int)(height / scaleFactor);
            
            // Only resize if dimensions have changed AND we haven't resized recently
            // Also check if the current render dimensions are significantly different
            boolean dimensionsChanged = (lastWidth != width || lastHeight != height);
            boolean renderDimensionsWrong = Math.abs(fsrProcessor.getRenderWidth() - targetRenderWidth) > 5 ||
                                           Math.abs(fsrProcessor.getRenderHeight() - targetRenderHeight) > 5;
            
            if ((dimensionsChanged || renderDimensionsWrong) && 
                (currentTime - lastResizeTime > RESIZE_THROTTLE_MS)) {
                
                AMDium.LOGGER.info("Resizing FSR buffers from " + fsrProcessor.getRenderWidth() + "x" + 
                                  fsrProcessor.getRenderHeight() + " to target " + targetRenderWidth + "x" + targetRenderHeight);
                
                fsrProcessor.resizeBuffers(width, height);
                lastResizeTime = currentTime;
                lastWidth = width;
                lastHeight = height;
            }
            
            // Set the flag to prevent recursion
            isProcessingFrame = true;
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in render start", e);
            // Report error to AMDium for handling
            AMDium.getInstance().reportError();
            // Reset to default framebuffer to prevent black screen
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
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
                fsrProcessor.processFrame(originalFramebuffer.fbo, System.currentTimeMillis());
            }
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in render end", e);
            // Report error to AMDium for handling
            AMDium.getInstance().reportError();
            // Make sure we reset to the default framebuffer to prevent black screen
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        } finally {
            // Reset the flag
            isProcessingFrame = false;
        }
    }
    
    @Inject(method = "onResized", at = @At("RETURN"))
    private void onResized(int width, int height, CallbackInfo ci) {
        if (!AMDium.getInstance().isFSREnabled()) return;
        
        try {
            // Store the new dimensions but don't resize immediately
            // The render method will handle the resize on the next frame
            lastWidth = width;
            lastHeight = height;
            lastResizeTime = 0; // Reset the timer to force a resize on next frame
            
            AMDium.LOGGER.info("Screen resized to " + width + "x" + height + ", will update FSR buffers on next frame");
        } catch (Exception e) {
            AMDium.LOGGER.error("Error in onResized", e);
            // Report error to AMDium for handling
            AMDium.getInstance().reportError();
        }
    }
} 