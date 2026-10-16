package com.eltavine.the_abyssal_sovereign.mixin.client;

import com.eltavine.the_abyssal_sovereign.client.guard.SovereignGuard;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The integrity guard's second driver, independent of the event bus.
 *
 * <p>The client-tick event that pumps the rest of the mod can be taken away with a single
 * {@code EVENT_BUS.unregister}, and if the guard ran only there it would be silenced by the very
 * removal it exists to catch. A transform baked into {@code Minecraft#tick} cannot be unregistered, so
 * the guard keeps its own clock here and goes on judging after the event subscribers are gone.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTickMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void the_abyssal_sovereign$guardTick(CallbackInfo ci) {
        SovereignGuard.onTickMixin();
    }
}
