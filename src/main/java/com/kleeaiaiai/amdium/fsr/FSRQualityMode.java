package com.kleeaiaiai.amdium.fsr;

public enum FSRQualityMode {
    ULTRA_QUALITY(1.3f, "Ultra Quality"),
    QUALITY(1.5f, "Quality"),
    BALANCED(1.7f, "Balanced"),
    PERFORMANCE(2.0f, "Performance"),
    ULTRA_PERFORMANCE(3.0f, "Ultra Performance");
    
    private final float scaleFactor;
    private final String displayName;
    
    FSRQualityMode(float scaleFactor, String displayName) {
        this.scaleFactor = scaleFactor;
        this.displayName = displayName;
    }
    
    public float getScaleFactor() {
        return scaleFactor;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public int calculateRenderWidth(int displayWidth) {
        return (int) (displayWidth / scaleFactor);
    }
    
    public int calculateRenderHeight(int displayHeight) {
        return (int) (displayHeight / scaleFactor);
    }
} 