package com.eltavine.the_abyssal_sovereign.client.effect;

import com.eltavine.the_abyssal_sovereign.client.SovereignClient;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Holds the overworld at midnight under a thunderstorm for as long as the Sovereign is invoked.
 *
 * <p>In single player the integrated server owns time and weather, so the lock is applied there and
 * every client sees it for real. It is re-asserted every tick rather than set once: sleeping skips to
 * morning and clears the weather, and the weather cycle would end the storm on its own, so a one-shot
 * change would not hold. No gamerule is touched, so nothing about the lock is written into the save.
 *
 * <p>On a multiplayer client there is no such authority; the same intent lands as a local storm drawn
 * over this client's sky only.
 */
public final class WeatherDominion {

    /** Midnight on the overworld's 24000-tick day (0 is sunrise, 6000 noon). */
    private static final long MIDNIGHT = 18000L;
    private static final long DAY = 24000L;

    private static final AtomicBoolean SERVER_TASK_QUEUED = new AtomicBoolean();

    private WeatherDominion() {
    }

    public static void enforce() {
        if (!SovereignClient.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            if (SERVER_TASK_QUEUED.compareAndSet(false, true)) {
                server.execute(() -> {
                    try {
                        if (SovereignClient.isActive()) {
                            lock(server.overworld());
                        }
                    } finally {
                        SERVER_TASK_QUEUED.set(false);
                    }
                });
            }
            return;
        }
        ClientLevel level = minecraft.level;
        if (level != null && !level.dimensionType().hasFixedTime()) {
            level.setRainLevel(1.0F);
            level.setThunderLevel(1.0F);
        }
    }

    private static void lock(ServerLevel overworld) {
        // Keep the day count so the moon phase stays put; only the time of day is pinned.
        long dayTime = overworld.getDayTime();
        overworld.setDayTime(dayTime - Math.floorMod(dayTime, DAY) + MIDNIGHT);
        if (!overworld.isRaining() || !overworld.isThundering()) {
            overworld.setWeatherParameters(0, Integer.MAX_VALUE, true, true);
        }
    }
}
