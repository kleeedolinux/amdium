package com.kleeaiaiai.amdium.fsr;

public enum FSRType {
    FSR_1("FSR 1.0", "Basic upscaling with edge detection"),
    FSR_2("FSR 2.0", "Temporal upscaling with motion vectors"),
    FSR_3("FSR 3.0", "Advanced upscaling with frame generation");
    
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