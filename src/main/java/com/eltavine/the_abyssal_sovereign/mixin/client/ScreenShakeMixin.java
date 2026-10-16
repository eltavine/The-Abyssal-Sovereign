package com.eltavine.the_abyssal_sovereign.mixin.client;

import com.eltavine.the_abyssal_sovereign.client.ScreenTremor;
import com.eltavine.the_abyssal_sovereign.client.guard.SovereignGuard;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shakes every screen for as long as the Sovereign is invoked.
 *
 * <p>{@code renderWithTooltip} is {@code final} and is the single choke point the client uses to draw
 * a screen — options, pause, inventory, chat, the mod list and every other {@link Screen}. Container
 * screens replicate {@code Screen#render} instead of calling super, so injecting {@code render} would
 * miss them; wrapping this method catches those too.
 */
@Mixin(Screen.class)
public abstract class ScreenShakeMixin {

    @Inject(
            method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("HEAD"))
    private void the_abyssal_sovereign$tremorBegin(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ScreenTremor.push(graphics);
    }

    @Inject(
            method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("RETURN"))
    private void the_abyssal_sovereign$tremorEnd(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ScreenTremor.pop(graphics);
        // After the pop, so the lockdown is steady and covers the screen and its tooltips.
        SovereignGuard.renderOverlay(graphics);
    }
}
