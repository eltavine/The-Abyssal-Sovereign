package com.eltavine.the_abyssal_sovereign.client;

import com.eltavine.the_abyssal_sovereign.TheAbyssalSovereign;
import com.eltavine.the_abyssal_sovereign.client.render.SovereignWorldRenderer;
import com.eltavine.the_abyssal_sovereign.config.SovereignConfig;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Client game-bus wiring: the per-tick pump, the world render hook, the chat invocation, and the
 * "daemon is mandatory" world-entry gate.
 *
 * <p>All handlers are client-only. None of them register anything with the game; they observe events
 * the platform already fires and route them to the orchestrator.
 */
@EventBusSubscriber(modid = TheAbyssalSovereign.MODID, value = Dist.CLIENT)
public final class ClientGameEvents {

    private ClientGameEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // Before this tick's snapshot goes out, so the renderer always steps to the previous tick's
        // reply instead of racing the bridge thread for the new one.
        SovereignWorldRenderer.tick();
        SovereignClient.onClientTick();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        SovereignWorldRenderer.render(event);
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        String message = event.getMessage().trim();
        SovereignConfig config = SovereignClient.config();
        if (matches(message, config.invocationPhrase()) || matches(message, config.commandToken())) {
            event.setCanceled(true);
            SovereignClient.invoke();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.the_abyssal_sovereign.invoked"), false);
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // The daemon is mandatory: without it there is no boss brain, so entry is refused.
        if (!SovereignClient.ensureStarted() && event.getConnection() != null) {
            event.getConnection().disconnect(Component.translatable("message.the_abyssal_sovereign.daemon_missing"));
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SovereignClient.onWorldLeave();
    }

    private static boolean matches(String message, String phrase) {
        return phrase != null && !phrase.isEmpty() && message.equalsIgnoreCase(phrase.trim());
    }
}
