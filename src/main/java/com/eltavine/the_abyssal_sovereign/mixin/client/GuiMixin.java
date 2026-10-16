package com.eltavine.the_abyssal_sovereign.mixin.client;

import com.eltavine.the_abyssal_sovereign.client.hud.SovereignHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Sovereign's HUD by appending to the vanilla GUI render, rather than registering an
 * overlay layer. This is the "no registration" boundary made concrete: the banner, title and
 * screen-dimming are painted straight onto the existing HUD pass.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"))
    private void the_abyssal_sovereign$renderHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        SovereignHud.render(guiGraphics, deltaTracker);
    }
}
