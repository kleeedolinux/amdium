package com.kleeaiaiai.amdium.mixin;

import com.kleeaiaiai.amdium.config.AMDiumOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameOptionsScreen.class)
public class GameOptionsScreenMixin extends Screen {
    protected GameOptionsScreenMixin(Text title) {
        super(title);
    }
    
    @Inject(method = "init", at = @At("RETURN"))
    private void addFSRButton(CallbackInfo ci) {
        ButtonWidget fsrButton = ButtonWidget.builder(
            Text.literal("AMD FSR Settings").formatted(Formatting.GOLD), 
            button -> this.client.setScreen(new AMDiumOptionsScreen(this))
        ).dimensions(this.width / 2 - 155, this.height / 6 + 144 - 6, 150, 20).build();
        
        this.addDrawableChild(fsrButton);
    }
} 