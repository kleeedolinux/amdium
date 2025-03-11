package com.kleeaiaiai.amdium.mixin;

import com.kleeaiaiai.amdium.config.AMDiumOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoOptionsScreen.class)
public class VideoOptionsScreenMixin extends Screen {
    protected VideoOptionsScreenMixin(Text title) {
        super(title);
    }
    
    @Inject(method = "init", at = @At("RETURN"))
    private void addFSRButton(CallbackInfo ci) {
        // Add FSR settings button at the top of the screen for better visibility
        // Make it stand out with a different style
        ButtonWidget fsrButton = ButtonWidget.builder(
            Text.literal("AMD FSR Settings").formatted(Formatting.GOLD), 
            button -> this.client.setScreen(new AMDiumOptionsScreen(this))
        ).dimensions(this.width / 2 - 155, 6, 150, 20).build();
        
        this.addDrawableChild(fsrButton);
    }
} 