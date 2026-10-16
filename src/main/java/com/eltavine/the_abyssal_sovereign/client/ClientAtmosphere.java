package com.eltavine.the_abyssal_sovereign.client;

import com.eltavine.the_abyssal_sovereign.TheAbyssalSovereign;
import com.eltavine.the_abyssal_sovereign.bridge.BossCommand;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Pulls the world's fog in and tints it abyssal while the Sovereign is present, driven by the
 * daemon's atmosphere command. Together with the screen-dimming in the HUD this is the "full
 * atmosphere" the boss carries; the screen darkens, the air closes in and the fog runs cold purple.
 */
@EventBusSubscriber(modid = TheAbyssalSovereign.MODID, value = Dist.CLIENT)
public final class ClientAtmosphere {

    private ClientAtmosphere() {
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!SovereignClient.isActive()) {
            return;
        }
        long now = Util.getMillis();
        event.setYaw(event.getYaw() + ScreenTremor.cameraYaw(now));
        event.setPitch(event.getPitch() + ScreenTremor.cameraPitch(now));
        event.setRoll(event.getRoll() + ScreenTremor.cameraRoll(now));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        BossCommand.BossState state = SovereignClient.bridge().bossState();
        if (!state.present()) {
            return;
        }
        float fog = SovereignClient.bridge().atmosphere().fog();
        if (fog <= 0.01F) {
            return;
        }
        // Stronger fog closes the far plane toward a claustrophobic 24 blocks.
        float far = event.getFarPlaneDistance();
        float target = Mth.lerp(fog, far, 24.0F);
        event.setFarPlaneDistance(Math.min(far, target));
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), 2.0F));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        BossCommand.BossState state = SovereignClient.bridge().bossState();
        if (!state.present()) {
            return;
        }
        float sky = SovereignClient.bridge().atmosphere().sky();
        if (sky <= 0.01F) {
            return;
        }
        // Blend toward a deep abyssal purple as the sky darkens.
        float t = Mth.clamp(sky, 0.0F, 1.0F) * 0.85F;
        event.setRed(Mth.lerp(t, event.getRed(), 0.06F));
        event.setGreen(Mth.lerp(t, event.getGreen(), 0.01F));
        event.setBlue(Mth.lerp(t, event.getBlue(), 0.10F));
    }
}
