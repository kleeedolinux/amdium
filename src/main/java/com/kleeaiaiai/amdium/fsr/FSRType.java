package com.kleeaiaiai.amdium.fsr;

public enum FSRType {
    FSR_1("FSR 1.0", "Enhanced upscaling with edge detection and sharpening");
    
    private final String displayName;
    private final String description;
    
    FSRType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
} 