package com.kleeaiaiai.amdium.config;

import com.kleeaiaiai.amdium.AMDium;
import com.kleeaiaiai.amdium.fsr.FSRProcessor;
import com.kleeaiaiai.amdium.fsr.FSRQualityMode;
import com.kleeaiaiai.amdium.fsr.FSRType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class AMDiumOptionsScreen extends Screen {
    private final Screen parent;
    private final AMDiumConfig config;
    private boolean settingsChanged = false;
    
    // Button positions
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 24;
    private static final int TITLE_COLOR = 0xFFFFFF;
    
    public AMDiumOptionsScreen(Screen parent) {
        super(Text.translatable("screen.amdium.config"));
        this.parent = parent;
        this.config = AMDium.getInstance().getConfig();
    }
    
    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 6;
        
        // FSR Enable/Disable button
        CyclingButtonWidget<Boolean> enableButton = CyclingButtonWidget.onOffBuilder(config.isEnabled())
            .build(
                centerX - BUTTON_WIDTH / 2, 
                startY, 
                BUTTON_WIDTH, 
                BUTTON_HEIGHT,
                Text.translatable("option.amdium.enabled"),
                (button, enabled) -> {
                    config.setEnabled(enabled);
                    settingsChanged = true;
                }
            );
        enableButton.setTooltip(Tooltip.of(Text.literal("Enable or disable AMD FSR upscaling")));
        this.addDrawableChild(enableButton);
        
        // FSR Type selection button
        CyclingButtonWidget<FSRType> typeButton = CyclingButtonWidget.builder(
                (FSRType type) -> Text.literal(type.getDisplayName())
            )
            .values(FSRType.values())
            .initially(config.getFsrType())
            .build(
                centerX - BUTTON_WIDTH / 2, 
                startY + BUTTON_SPACING, 
                BUTTON_WIDTH, 
                BUTTON_HEIGHT,
                Text.translatable("option.amdium.fsr_type"),
                (button, fsrType) -> {
                    config.setFsrType(fsrType);
                    settingsChanged = true;
                }
            );
        typeButton.setTooltip(Tooltip.of(Text.literal("Select FSR version:\nFSR 1.0: Basic upscaling with edge preservation")));
        this.addDrawableChild(typeButton);
        
        // FSR Quality Mode selection button
        CyclingButtonWidget<FSRQualityMode> qualityButton = CyclingButtonWidget.builder(
                (FSRQualityMode mode) -> Text.literal(mode.getDisplayName())
            )
            .values(FSRQualityMode.values())
            .initially(config.getQualityMode())
            .build(
                centerX - BUTTON_WIDTH / 2, 
                startY + BUTTON_SPACING * 2, 
                BUTTON_WIDTH, 
                BUTTON_HEIGHT,
                Text.translatable("option.amdium.quality_mode"),
                (button, qualityMode) -> {
                    config.setQualityMode(qualityMode);
                    settingsChanged = true;
                }
            );
        qualityButton.setTooltip(Tooltip.of(Text.literal("Select quality vs. performance tradeoff\nHigher quality = lower performance boost")));
        this.addDrawableChild(qualityButton);
        
        // Sharpness slider
        final Text sharpnessText = Text.translatable("option.amdium.sharpness");
        SliderWidget sharpnessSlider = new SliderWidget(
            centerX - BUTTON_WIDTH / 2, 
            startY + BUTTON_SPACING * 3, 
            BUTTON_WIDTH, 
            BUTTON_HEIGHT,
            Text.of(sharpnessText.getString() + ": " + String.format("%.2f", config.getSharpness())),
            config.getSharpness()
        ) {
            private float currentValue = config.getSharpness();
            
            @Override
            protected void updateMessage() {
                setMessage(Text.of(sharpnessText.getString() + ": " + String.format("%.2f", currentValue)));
            }
            
            @Override
            protected void applyValue() {
                currentValue = (float) this.value;
                config.setSharpness(currentValue);
                settingsChanged = true;
            }
        };
        sharpnessSlider.setTooltip(Tooltip.of(Text.literal("Adjust image sharpness\nHigher values = sharper image but may introduce artifacts")));
        this.addDrawableChild(sharpnessSlider);
        
        // Auto-Enable toggle button
        CyclingButtonWidget<Boolean> autoEnableButton = CyclingButtonWidget.onOffBuilder(config.isAutoEnable())
            .build(
                centerX - BUTTON_WIDTH / 2, 
                startY + BUTTON_SPACING * 4, 
                BUTTON_WIDTH, 
                BUTTON_HEIGHT,
                Text.translatable("option.amdium.auto_enable"),
                (button, enabled) -> {
                    config.setAutoEnable(enabled);
                    settingsChanged = true;
                }
            );
        autoEnableButton.setTooltip(Tooltip.of(Text.literal("Automatically enable FSR when FPS drops below threshold")));
        this.addDrawableChild(autoEnableButton);
        
        // Done button
        ButtonWidget doneButton = ButtonWidget.builder(Text.translatable("gui.done"), button -> {
            if (settingsChanged) {
                config.save();
                // Safely apply changes before closing
                try {
                    AMDium.getInstance().restartFSRProcessor();
                } catch (Exception e) {
                    AMDium.LOGGER.error("Error applying FSR changes", e);
                }
            }
            this.close();
        }).dimensions(centerX - BUTTON_WIDTH / 2, startY + BUTTON_SPACING * 5, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addDrawableChild(doneButton);
    }
    
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        
        
        context.drawCenteredTextWithShadow(
            this.textRenderer, 
            this.title, 
            this.width / 2, 
            20, 
            TITLE_COLOR
        );
        
        
        String infoText = "FSR " + config.getFsrType().getDisplayName() + " - " + 
                         config.getQualityMode().getDisplayName() + " (" + 
                         String.format("%.1fx", config.getQualityMode().getScaleFactor()) + " scale)";
        
        context.drawCenteredTextWithShadow(
            this.textRenderer, 
            Text.literal(infoText).formatted(Formatting.GOLD), 
            this.width / 2, 
            40, 
            TITLE_COLOR
        );
        
        
        String statusText = "Status: " + (AMDium.getInstance().isFSREnabled() ? "Active" : "Inactive");
        Formatting statusColor = AMDium.getInstance().isFSREnabled() ? Formatting.GREEN : Formatting.RED;
        
        context.drawTextWithShadow(
            this.textRenderer,
            Text.literal(statusText).formatted(statusColor),
            10,
            10,
            TITLE_COLOR
        );
        
        
        if (settingsChanged) {
            context.drawTextWithShadow(
                this.textRenderer,
                Text.literal("* Settings changed").formatted(Formatting.YELLOW),
                this.width - 120,
                10,
                TITLE_COLOR
            );
        }
        
        super.render(context, mouseX, mouseY, delta);
    }
    
    @Override
    public void close() {
        if (settingsChanged) {
            config.save();
        }
        this.client.setScreen(this.parent);
    }
} 