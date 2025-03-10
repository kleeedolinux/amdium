package com.kleeaiaiai.amdium.config;

import com.kleeaiaiai.amdium.AMDium;
import com.kleeaiaiai.amdium.fsr.FSRQualityMode;
import com.kleeaiaiai.amdium.fsr.FSRType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class AMDiumModMenuIntegration {
    public Screen createConfigScreen(Screen parent) {
        return new AMDiumConfigScreen(parent);
    }
    
    private static class AMDiumConfigScreen extends Screen {
        private final Screen parent;
        private final AMDiumConfig config;
        private int buttonY;
        private static final int BUTTON_HEIGHT = 20;
        private static final int BUTTON_SPACING = 24;
        
        protected AMDiumConfigScreen(Screen parent) {
            super(Text.translatable("screen.amdium.config"));
            this.parent = parent;
            this.config = AMDium.getInstance().getConfig();
        }
        
        @Override
        protected void init() {
            buttonY = this.height / 6;
            
            // Add a button to toggle FSR
            addButton(Text.translatable("option.amdium.enabled", config.isEnabled() ? "ON" : "OFF"),
                    button -> {
                        config.setEnabled(!config.isEnabled());
                        button.setMessage(Text.translatable("option.amdium.enabled", config.isEnabled() ? "ON" : "OFF"));
                    });
            
            // Add a button to toggle auto-enable
            addButton(Text.translatable("option.amdium.auto_enable", config.isAutoEnable() ? "ON" : "OFF"),
                    button -> {
                        config.setAutoEnable(!config.isAutoEnable());
                        button.setMessage(Text.translatable("option.amdium.auto_enable", config.isAutoEnable() ? "ON" : "OFF"));
                    });
            
            // Add a button to cycle through FSR types
            addButton(Text.translatable("option.amdium.fsr_type", config.getFsrType().getDisplayName()),
                    button -> {
                        FSRType[] types = FSRType.values();
                        int nextIndex = (config.getFsrType().ordinal() + 1) % types.length;
                        config.setFsrType(types[nextIndex]);
                        button.setMessage(Text.translatable("option.amdium.fsr_type", config.getFsrType().getDisplayName()));
                    });
            
            // Add a button to cycle through quality modes
            addButton(Text.translatable("option.amdium.quality_mode", config.getQualityMode().getDisplayName()),
                    button -> {
                        FSRQualityMode[] modes = FSRQualityMode.values();
                        int nextIndex = (config.getQualityMode().ordinal() + 1) % modes.length;
                        config.setQualityMode(modes[nextIndex]);
                        button.setMessage(Text.translatable("option.amdium.quality_mode", config.getQualityMode().getDisplayName()));
                    });
            
            // Add a button to toggle frame generation
            addButton(Text.translatable("option.amdium.frame_generation", config.isFrameGeneration() ? "ON" : "OFF"),
                    button -> {
                        config.setFrameGeneration(!config.isFrameGeneration());
                        button.setMessage(Text.translatable("option.amdium.frame_generation", config.isFrameGeneration() ? "ON" : "OFF"));
                    });
            
            // Add a done button
            addButton(Text.translatable("gui.done"),
                    button -> {
                        config.save();
                        this.client.setScreen(this.parent);
                    });
        }
        
        private void addButton(Text text, ButtonWidget.PressAction action) {
            this.addDrawableChild(new ButtonWidget.Builder(text, action)
                    .dimensions(this.width / 2 - 100, buttonY, 200, BUTTON_HEIGHT)
                    .build());
            buttonY += BUTTON_SPACING;
        }
        
        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            this.renderBackground(context);
            context.drawTextWithShadow(this.textRenderer, this.title, this.width / 2 - this.textRenderer.getWidth(this.title) / 2, 15, 0xFFFFFF);
            
            // Draw FSR type description
            String description = config.getFsrType().getDescription();
            context.drawTextWithShadow(this.textRenderer, description, 
                    this.width / 2 - this.textRenderer.getWidth(description) / 2, 
                    this.height / 6 + BUTTON_SPACING * 3 + 5, 0xAAAAAA);
            
            // Draw note about FSR 1.0 being activated by default
            String defaultNote = "FSR 1.0 is activated by default for best compatibility";
            context.drawTextWithShadow(this.textRenderer, defaultNote,
                    this.width / 2 - this.textRenderer.getWidth(defaultNote) / 2,
                    this.height - 40, 0xFFAA00);
            
            super.render(context, mouseX, mouseY, delta);
        }
    }
} 