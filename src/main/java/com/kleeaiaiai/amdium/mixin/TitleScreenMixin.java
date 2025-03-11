package com.kleeaiaiai.amdium.mixin;

import com.kleeaiaiai.amdium.config.AMDiumOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Text title) {
        super(title);
    }
    
    @Inject(method = "init", at = @At("RETURN"))
    private void addFSRButton(CallbackInfo ci) {
        // Add a small FSR button in the bottom right corner
        int buttonWidth = 60;
        int buttonHeight = 20;
        int x = this.width - buttonWidth - 5;
        int y = this.height - buttonHeight - 5;
        
        ButtonWidget fsrButton = ButtonWidget.builder(
            Text.literal("FSR").formatted(Formatting.GOLD),
            button -> this.client.setScreen(new AMDiumOptionsScreen(this))
        ).dimensions(x, y, buttonWidth, buttonHeight).build();
        
        this.addDrawableChild(fsrButton);
    }
} 