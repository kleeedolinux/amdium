package com.kleeaiaiai.amdium.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kleeaiaiai.amdium.AMDium;
import com.kleeaiaiai.amdium.fsr.FSRQualityMode;
import com.kleeaiaiai.amdium.fsr.FSRType;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class AMDiumConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("amdium.json").toFile();
    
    private boolean enabled = true;
    private boolean autoEnable = false;
    private FSRQualityMode qualityMode = FSRQualityMode.BALANCED;
    private FSRType fsrType = FSRType.FSR_1;
    private float sharpness = 0.7f;
    
    public void load() {
        try {
            if (CONFIG_FILE.exists()) {
                try (FileReader reader = new FileReader(CONFIG_FILE)) {
                    AMDiumConfig loaded = GSON.fromJson(reader, AMDiumConfig.class);
                    this.enabled = loaded.enabled;
                    this.autoEnable = loaded.autoEnable;
                    this.qualityMode = loaded.qualityMode;
                    this.fsrType = loaded.fsrType != null ? loaded.fsrType : FSRType.FSR_1;
                    this.sharpness = loaded.sharpness;
                }
                AMDium.LOGGER.info("Loaded AMDium configuration");
            } else {
                save();
                AMDium.LOGGER.info("Created default AMDium configuration");
            }
        } catch (IOException e) {
            AMDium.LOGGER.error("Failed to load AMDium configuration", e);
        }
    }
    
    public void save() {
        try {
            if (!CONFIG_FILE.exists()) {
                CONFIG_FILE.getParentFile().mkdirs();
                CONFIG_FILE.createNewFile();
            }
            
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            AMDium.LOGGER.error("Failed to save AMDium configuration", e);
        }
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public boolean isAutoEnable() {
        return autoEnable;
    }
    
    public void setAutoEnable(boolean autoEnable) {
        this.autoEnable = autoEnable;
    }
    
    public FSRQualityMode getQualityMode() {
        return qualityMode;
    }
    
    public void setQualityMode(FSRQualityMode qualityMode) {
        this.qualityMode = qualityMode;
    }
    
    public FSRType getFsrType() {
        return fsrType;
    }
    
    public void setFsrType(FSRType fsrType) {
        this.fsrType = fsrType;
    }
    
    public float getSharpness() {
        return sharpness;
    }
    
    public void setSharpness(float sharpness) {
        this.sharpness = Math.max(0.0f, Math.min(1.0f, sharpness));
    }
    
    /**
     * Gets the scaling factor for rendering (inverse of the quality mode's scale factor)
     * @return The scaling factor (e.g., 0.5 for 50% resolution)
     */
    public float getScalingFactor() {
        return 1.0f / qualityMode.getScaleFactor();
    }
} 