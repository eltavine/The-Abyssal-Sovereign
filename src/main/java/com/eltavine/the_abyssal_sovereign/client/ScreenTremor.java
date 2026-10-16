package com.eltavine.the_abyssal_sovereign.client;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Continuous screen shake for as long as the Sovereign is invoked.
 *
 * <p>Two consumers share the same clock: GUI code pushes a translation onto a {@link GuiGraphics}
 * pose so every screen, menu and HUD pass is displaced, and the camera event adds yaw, pitch and
 * roll so the world view shakes with them. The offset is a sum of incommensurate sines, so it never
 * settles into a simple bob.
 *
 * <p>Push and pop are paired by a depth count on the client thread. A push that finds the Sovereign
 * dormant does not touch the pose, and the matching pop is then a no-op, so an inactive frame cannot
 * unbalance the stack.
 */
public final class ScreenTremor {

    private static int depth;

    private ScreenTremor() {
    }

    public static void push(GuiGraphics graphics) {
        if (!SovereignClient.isActive()) {
            return;
        }
        depth++;
        long now = Util.getMillis();
        graphics.pose().pushPose();
        graphics.pose().translate(offsetX(now), offsetY(now), 0.0F);
    }

    public static void pop(GuiGraphics graphics) {
        if (depth <= 0) {
            return;
        }
        depth--;
        graphics.pose().popPose();
    }

    /** Horizontal GUI displacement in pixels. */
    public static float offsetX(long nowMillis) {
        double t = seconds(nowMillis);
        return (float) (Math.sin(t * 47.0D) * 1.8D
                + Math.sin(t * 71.0D) * 0.7D
                + Math.sin(t * 19.0D) * 0.6D);
    }

    /** Vertical GUI displacement in pixels. */
    public static float offsetY(long nowMillis) {
        double t = seconds(nowMillis);
        return (float) (Math.cos(t * 41.0D) * 1.6D
                + Math.sin(t * 67.0D) * 0.6D
                + Math.cos(t * 23.0D) * 0.6D);
    }

    public static float cameraYaw(long nowMillis) {
        double t = seconds(nowMillis);
        return (float) (Math.sin(t * 37.0D) * 0.3D + Math.sin(t * 61.0D) * 0.1D);
    }

    public static float cameraPitch(long nowMillis) {
        double t = seconds(nowMillis);
        return (float) (Math.cos(t * 43.0D) * 0.22D + Math.sin(t * 59.0D) * 0.08D);
    }

    public static float cameraRoll(long nowMillis) {
        double t = seconds(nowMillis);
        return (float) (Math.sin(t * 53.0D) * 0.35D + Math.cos(t * 67.0D) * 0.12D);
    }

    /**
     * {@link Util#getMillis()} counts from boot, so the phase stays in double: in float,
     * {@code Mth.sin}'s int table index saturates once {@code t * ω} passes ~2e5 rad (minutes to
     * hours of uptime) and the shake freezes flat.
     */
    private static double seconds(long nowMillis) {
        return nowMillis * 0.001D;
    }
}
