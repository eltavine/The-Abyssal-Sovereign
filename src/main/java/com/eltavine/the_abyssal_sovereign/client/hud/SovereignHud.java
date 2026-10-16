package com.eltavine.the_abyssal_sovereign.client.hud;

import com.eltavine.the_abyssal_sovereign.TheAbyssalSovereign;
import com.eltavine.the_abyssal_sovereign.bridge.BossCommand;
import com.eltavine.the_abyssal_sovereign.client.SovereignClient;
import com.eltavine.the_abyssal_sovereign.client.guard.SovereignGuard;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * The boss bar and its atmosphere, drawn directly rather than through any registered overlay.
 *
 * <p>Reuses the original mod's banner texture, glitching rainbow title and phase subtitle. The
 * health bar is intentionally always full: the Sovereign takes no damage and "cannot be shaken", so
 * the bar is a presence indicator, not a health gauge. A screen-dimming pass driven by the daemon's
 * atmosphere command sits behind it.
 */
public final class SovereignHud {

    private static final ResourceLocation BANNER =
            ResourceLocation.fromNamespaceAndPath(TheAbyssalSovereign.MODID, "textures/gui/boss_bar.png");
    private static final int TEXTURE_WIDTH = 1158;
    private static final int TEXTURE_HEIGHT = 378;
    private static final int TARGET_DRAW_WIDTH = 320;
    private static final int TOP_MARGIN = 8;
    private static final int TITLE_GAP = 12;
    private static final int PHASE_GAP = 26;
    private static final float TITLE_TARGET_SCALE = 1.85F;
    private static final float TITLE_MIN_SCALE = 0.85F;
    private static final String TITLE_KEY = "overlay.the_abyssal_sovereign.boss_title";
    private static final String PHASE_KEY = "phase.the_abyssal_sovereign.cataclysm";
    private static final String GLITCH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$%&*+-=<>?/!@";
    private static final int TITLE_SHADOW = 0xF0030303;

    private SovereignHud() {
    }

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        BossCommand.BossState state = SovereignClient.bridge().bossState();
        if (!state.present()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        int screenWidth = guiGraphics.guiWidth();
        int screenHeight = guiGraphics.guiHeight();

        drawDimming(guiGraphics, screenWidth, screenHeight);

        int maxWidth = Math.max(1, screenWidth - 16);
        float scale = Math.min(1.0F, Math.min(TARGET_DRAW_WIDTH / (float) TEXTURE_WIDTH, maxWidth / (float) TEXTURE_WIDTH));
        int drawWidth = Mth.floor(TEXTURE_WIDTH * scale);
        int drawHeight = Mth.floor(TEXTURE_HEIGHT * scale);
        int x = (screenWidth - drawWidth) / 2;
        int y = TOP_MARGIN;

        guiGraphics.blit(BANNER, x, y, drawWidth, drawHeight, 0.0F, 0.0F, TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        renderTitle(guiGraphics, minecraft.font, screenWidth, y + drawHeight + TITLE_GAP);
        renderPhase(guiGraphics, minecraft.font, screenWidth, y + drawHeight + TITLE_GAP + PHASE_GAP);

        // Stamped only once the bar has drawn, so a HUD cut short reads as suppressed.
        SovereignGuard.markHudRender();
    }

    private static void drawDimming(GuiGraphics guiGraphics, int width, int height) {
        BossCommand.Atmosphere atmosphere = SovereignClient.bridge().atmosphere();
        int alpha = Mth.clamp((int) (atmosphere.darken() * 150.0F), 0, 200);
        if (alpha <= 0) {
            return;
        }
        guiGraphics.fill(0, 0, width, height, alpha << 24);
    }

    private static void renderPhase(GuiGraphics guiGraphics, Font font, int screenWidth, int y) {
        String text = Component.translatable(PHASE_KEY).getString();
        long now = Util.getMillis();
        float pulse = 0.6F + 0.4F * Mth.sin(now * 0.01F);
        int alpha = 150 + (int) (pulse * 105.0F);
        int color = (Mth.clamp(alpha, 0, 255) << 24) | 0xFF1744;
        int textWidth = font.width(text);
        guiGraphics.drawString(font, text, (screenWidth - textWidth) / 2, y, color, true);
    }

    private static void renderTitle(GuiGraphics guiGraphics, Font font, int screenWidth, int y) {
        String title = Component.translatable(TITLE_KEY).getString();
        long now = Util.getMillis();
        long flickerFrame = now / 18L;

        int textWidth = font.width(title);
        float maxScale = (screenWidth - 24.0F) / Math.max(1.0F, textWidth);
        float scale = Mth.clamp(Math.min(TITLE_TARGET_SCALE, maxScale), TITLE_MIN_SCALE, TITLE_TARGET_SCALE);
        float originX = -textWidth / 2.0F;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(screenWidth / 2.0F, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);

        float cursorX = originX;
        for (int i = 0; i < title.length(); i++) {
            String glyph = String.valueOf(title.charAt(i));
            int glyphWidth = Math.max(1, font.width(glyph));

            float hue = ((now * 0.0068F) + i * 0.117F) % 1.0F;
            float pulse = 0.55F + 0.45F * Mth.sin(now * 0.039F + i * 2.1F);
            float jitterX = (noise((int) flickerFrame + i * 37) - 0.5F) * 5.0F;
            float jitterY = (noise((int) flickerFrame + i * 61) - 0.5F) * 4.4F;

            String glitch = ((flickerFrame + i) & 1L) == 0L
                    ? String.valueOf(GLITCH_ALPHABET.charAt(Math.floorMod((int) flickerFrame * 7 + i * 19, GLITCH_ALPHABET.length())))
                    : glyph;

            int mainColor = withAlpha(hsv(hue, 0.92F, 1.0F), 220 + (int) (pulse * 35.0F));
            int flareColor = withAlpha(hsv((hue + 0.33F) % 1.0F, 0.98F, 1.0F), 85 + (int) (pulse * 120.0F));

            guiGraphics.drawString(font, glyph, cursorX + 2.4F, 2.4F, TITLE_SHADOW, false);
            guiGraphics.drawString(font, glitch, cursorX - 2.6F + jitterX, -2.1F + jitterY, flareColor, false);
            guiGraphics.drawString(font, glyph, cursorX + jitterX * 0.4F, jitterY * 0.4F, mainColor, false);

            cursorX += glyphWidth;
        }
        guiGraphics.pose().popPose();
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int hsv(float hue, float saturation, float value) {
        return Mth.hsvToRgb(hue - Mth.floor(hue), saturation, value) & 0x00FFFFFF;
    }

    private static float noise(int seed) {
        int x = seed;
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        return (x & 0x7FFFFFFF) / 2147483647.0F;
    }
}
