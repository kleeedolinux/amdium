package com.kleeaiaiai.amdium;

import com.kleeaiaiai.amdium.config.AMDiumConfig;
import com.kleeaiaiai.amdium.config.AMDiumOptionsScreen;
import com.kleeaiaiai.amdium.fsr.FSRProcessor;
import com.kleeaiaiai.amdium.fsr.FSRType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AMDium implements ClientModInitializer {
    public static final String MOD_ID = "amdium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static AMDium INSTANCE;
    private FSRProcessor fsrProcessor;
    private AMDiumConfig config;
    private KeyBinding toggleFSRKey;
    private KeyBinding openOptionsKey;
    private int lastFps = 0;
    private long lastFpsCheckTime = 0;
    private static final long FPS_CHECK_INTERVAL = 5000; // Check FPS every 5 seconds
    private static final int LOW_FPS_THRESHOLD = 40; // FPS threshold to auto-enable FSR
    private boolean fsrInitialized = false;
    private boolean hasError = false;
    private int errorCount = 0;
    private static final int MAX_ERROR_COUNT = 3; // Maximum number of errors before disabling FSR
    
    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        LOGGER.info("Initializing AMDium - FSR for Minecraft");
        
        config = new AMDiumConfig();
        config.load();
        
        // Always start with FSR 1.0 enabled for best compatibility
        config.setFsrType(FSRType.FSR_1);
        if (!config.isEnabled()) {
            config.setEnabled(true);
            config.save();
            LOGGER.info("Auto-activated FSR 1.0 at startup");
        }
        
        registerKeybindings();
        
        // Register key press handler
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            // Initialize FSR after client is fully started
            client.execute(() -> {
                try {
                    initializeFSR();
                } catch (Exception e) {
                    LOGGER.error("Failed to initialize FSR during client start", e);
                }
            });
        });
        
        // Clean up FSR processor when the client stops
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            cleanupFSR();
        });
    }
    
    private void initializeFSR() {
        try {
            if (!fsrInitialized && !hasError) {
                // Make sure we're on the main thread
                if (!MinecraftClient.getInstance().isOnThread()) {
                    LOGGER.warn("Attempting to initialize FSR from non-main thread, deferring");
                    return;
                }
                
                // Clean up any existing resources first
                cleanupFSR();
                
                // Wait for any pending OpenGL operations
                GL11.glFinish();
                
                // Create and initialize the FSR processor
                fsrProcessor = new FSRProcessor();
                fsrProcessor.initialize();
                
                // Test with current framebuffer
                testFSRProcessor();
                
                fsrInitialized = true;
                errorCount = 0;
                hasError = false;
                LOGGER.info("FSR processor initialized successfully");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize FSR processor", e);
            handleError();
        }
    }
    
    private void testFSRProcessor() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getFramebuffer() == null || client.getFramebuffer().fbo <= 0) {
            throw new IllegalStateException("Invalid framebuffer for FSR test");
        }
        
        try {
            // Wait for any pending operations
            GL11.glFinish();
            
            // Process a test frame
            fsrProcessor.processFrame(client.getFramebuffer().fbo, System.currentTimeMillis());
            
            // Ensure it completes
            GL11.glFinish();
            
            LOGGER.info("FSR test frame processed successfully");
        } catch (Exception e) {
            LOGGER.error("FSR test frame processing failed", e);
            throw e;
        } finally {
            // Always reset to default framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }
    
    private void cleanupFSR() {
        try {
            // Make sure we're on the main thread
            if (!MinecraftClient.getInstance().isOnThread()) {
                LOGGER.warn("Attempting to cleanup FSR from non-main thread, deferring");
                return;
            }
            
            if (fsrProcessor != null) {
                // Wait for pending operations
                GL11.glFinish();
                
                // Cleanup processor
                fsrProcessor.cleanup();
                fsrProcessor = null;
                fsrInitialized = false;
                
                LOGGER.info("FSR processor cleaned up");
            }
            
            // Clean up static resources
            FSRProcessor.cleanupQuad();
            
            // Reset OpenGL state
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL20.glUseProgram(0);
            GL11.glFinish();
            
        } catch (Exception e) {
            LOGGER.error("Error cleaning up FSR processor", e);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }
    
    private void handleError() {
        errorCount++;
        
        try {
            // Reset OpenGL state
            GL11.glFinish();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL20.glUseProgram(0);
            
            if (errorCount >= MAX_ERROR_COUNT) {
                hasError = true;
                config.setEnabled(false);
                config.save();
                
                LOGGER.error("Disabling FSR due to too many errors");
                cleanupFSR();
                
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.inGameHud != null) {
                    client.inGameHud.getChatHud().addMessage(
                        net.minecraft.text.Text.literal("§c[AMDium] FSR has been disabled due to errors. Press F10 to try again.")
                    );
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error in error handler", e);
        } finally {
            // Always ensure we're back to default framebuffer
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }
    
    private void registerKeybindings() {
        toggleFSRKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.amdium.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                "category.amdium.keybinds"
        ));
        
        openOptionsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.amdium.options",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                "category.amdium.keybinds"
        ));
    }
    
    public static AMDium getInstance() {
        return INSTANCE;
    }
    
    public FSRProcessor getFSRProcessor() {
        return fsrProcessor;
    }
    
    public AMDiumConfig getConfig() {
        return config;
    }
    
    public boolean isFSREnabled() {
        return config.isEnabled() && !hasError;
    }
    
    public void toggleFSR() {
        if (hasError) {
            hasError = false;
            errorCount = 0;
            config.setEnabled(true);
            config.save();
            
            cleanupFSR();
            initializeFSR();
            LOGGER.info("Attempting to re-enable FSR");
        } else {
            config.setEnabled(!config.isEnabled());
            config.save();
            LOGGER.info("FSR " + (config.isEnabled() ? "enabled" : "disabled"));
        }
    }
    
    public void restartFSRProcessor() {
        try {
            if (!MinecraftClient.getInstance().isOnThread()) {
                LOGGER.warn("Attempting to restart FSR from non-main thread, deferring");
                return;
            }
            
            LOGGER.info("Restarting FSR processor");
            
            // Clean up existing resources
            cleanupFSR();
            
            // Wait for OpenGL to finish
            GL11.glFinish();
            
            // Reset OpenGL state
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL20.glUseProgram(0);
            
            // Initialize new processor
            initializeFSR();
            
            // Resize buffers to current screen size
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getWindow() != null && fsrProcessor != null) {
                int width = mc.getWindow().getFramebufferWidth();
                int height = mc.getWindow().getFramebufferHeight();
                fsrProcessor.resizeBuffers(width, height);
            }
            
            LOGGER.info("FSR processor restarted successfully");
            errorCount = 0;
            hasError = false;
            
        } catch (Exception e) {
            LOGGER.error("Failed to restart FSR processor", e);
            handleError();
        }
    }
    
    public KeyBinding getToggleFSRKey() {
        return toggleFSRKey;
    }
    
    public void reportError() {
        handleError();
    }
} 