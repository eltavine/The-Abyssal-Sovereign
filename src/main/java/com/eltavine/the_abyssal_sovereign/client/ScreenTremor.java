package com.eltavine.the_abyssal_sovereign.client;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

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
        float t = nowMillis * 0.001F;
        return Mth.sin(t * 47.0F) * 9.0F
                + Mth.sin(t * 113.0F) * 5.0F
                + Mth.sin(t * 19.0F) * 3.0F;
    }

    /** Vertical GUI displacement in pixels. */
    public static float offsetY(long nowMillis) {
        float t = nowMillis * 0.001F;
        return Mth.cos(t * 41.0F) * 8.0F
                + Mth.sin(t * 97.0F) * 4.5F
                + Mth.cos(t * 23.0F) * 3.0F;
    }

    public static float cameraYaw(long nowMillis) {
        float t = nowMillis * 0.001F;
        return Mth.sin(t * 37.0F) * 1.7F + Mth.sin(t * 91.0F) * 0.8F;
    }

    public static float cameraPitch(long nowMillis) {
        float t = nowMillis * 0.001F;
        return Mth.cos(t * 43.0F) * 1.3F + Mth.sin(t * 79.0F) * 0.6F;
    }

    public static float cameraRoll(long nowMillis) {
        float t = nowMillis * 0.001F;
        return Mth.sin(t * 53.0F) * 2.4F + Mth.cos(t * 101.0F) * 1.1F;
    }
}
