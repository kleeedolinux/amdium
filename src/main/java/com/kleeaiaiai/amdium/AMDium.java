package com.kleeaiaiai.amdium;

import com.kleeaiaiai.amdium.config.AMDiumConfig;
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
        
        if (!config.isEnabled()) {
            config.setEnabled(true);
            config.setFsrType(FSRType.FSR_3);
            config.save();
            LOGGER.info("Auto-activated FSR 3.0 at startup");
        }
        
        registerKeybindings();
        
        // Register key press handler using client tick events
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleFSRKey.wasPressed()) {
                toggleFSR();
            }
            
            // Auto-enable FSR based on FPS if enabled
            if (config.isAutoEnable() && !config.isEnabled() && !hasError) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFpsCheckTime > FPS_CHECK_INTERVAL) {
                    checkFpsAndEnableFSR(client);
                    lastFpsCheckTime = currentTime;
                }
            }
        });
        
        // Initialize FSR processor when the client starts
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            initializeFSR();
        });
        
        // Clean up FSR processor when the client stops
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            cleanupFSR();
        });
    }
    
    private void initializeFSR() {
        try {
            if (!fsrInitialized && !hasError) {
                // Start with FSR 1.0 for best compatibility
                config.setFsrType(FSRType.FSR_1);
                
                fsrProcessor = new FSRProcessor();
                fsrProcessor.initialize();
                
                // Test the FSR processor with a dummy frame to ensure it works
                testFSRProcessor();
                
                fsrInitialized = true;
                errorCount = 0; // Reset error count on successful initialization
                LOGGER.info("FSR processor initialized with type: " + config.getFsrType().getDisplayName());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize FSR processor", e);
            handleError();
        }
    }
    
    /**
     * Test the FSR processor with a dummy frame to ensure it works
     */
    private void testFSRProcessor() {
        try {
            // Get the current Minecraft framebuffer
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getFramebuffer() != null && client.getFramebuffer().fbo > 0) {
                // Try to process a single frame
                fsrProcessor.processFrame(client.getFramebuffer().fbo, System.currentTimeMillis());
                LOGGER.info("FSR test frame processed successfully");
            }
        } catch (Exception e) {
            LOGGER.error("FSR test frame processing failed", e);
            throw e; // Re-throw to trigger error handling
        }
    }
    
    private void cleanupFSR() {
        try {
            if (fsrProcessor != null) {
                fsrProcessor.cleanup();
                fsrInitialized = false;
                LOGGER.info("FSR processor cleaned up");
            }
            
            // Clean up static resources
            FSRProcessor.cleanupQuad();
        } catch (Exception e) {
            LOGGER.error("Error cleaning up FSR processor", e);
            // Make sure we reset to the default framebuffer to prevent black screen
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }
    
    private void handleError() {
        errorCount++;
        
        // Reset to default framebuffer to prevent black screen
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        
        if (errorCount >= MAX_ERROR_COUNT) {
            // Disable FSR after too many errors
            hasError = true;
            config.setEnabled(false);
            config.save();
            LOGGER.error("Disabling FSR due to too many errors");
            
            // Clean up any resources
            cleanupFSR();
            
            // Show a message to the user
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    client.inGameHud.getChatHud().addMessage(
                        net.minecraft.text.Text.literal("§c[AMDium] FSR has been disabled due to errors. Press F10 to try again.")
                    );
                }
            } catch (Exception e) {
                LOGGER.error("Failed to show error message to user", e);
            }
        } else if (config.getFsrType() != FSRType.FSR_1) {
            // Try falling back to FSR 1.0 which is more stable
            LOGGER.info("Falling back to FSR 1.0 due to error");
            config.setFsrType(FSRType.FSR_1);
            config.save();
            
            // Reinitialize with FSR 1.0
            cleanupFSR();
            initializeFSR();
        }
    }
    
    private void checkFpsAndEnableFSR(MinecraftClient client) {
        int currentFps = client.getCurrentFps();
        
        // If FPS is below threshold, enable FSR
        if (currentFps < LOW_FPS_THRESHOLD) {
            if (!config.isEnabled()) {
                LOGGER.info("Auto-enabling FSR due to low FPS: " + currentFps);
                config.setEnabled(true);
                config.save();
            }
        }
        
        lastFps = currentFps;
    }
    
    private void registerKeybindings() {
        toggleFSRKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.amdium.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
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
            // If there was an error, try to reset and reinitialize
            hasError = false;
            errorCount = 0;
            config.setFsrType(FSRType.FSR_1); // Use the most stable version
            config.setEnabled(true);
            config.save();
            
            cleanupFSR();
            initializeFSR();
            LOGGER.info("Attempting to re-enable FSR after error");
        } else {
            config.setEnabled(!config.isEnabled());
            config.save();
            LOGGER.info("FSR " + config.getFsrType().getDisplayName() + " " + (config.isEnabled() ? "enabled" : "disabled"));
        }
    }
    
    public KeyBinding getToggleFSRKey() {
        return toggleFSRKey;
    }
    
    public void reportError() {
        handleError();
    }
} 