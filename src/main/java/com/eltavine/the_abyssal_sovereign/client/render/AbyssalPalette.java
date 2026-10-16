package com.eltavine.the_abyssal_sovereign.client.render;

import net.minecraft.util.Mth;

/**
 * The Sovereign's colours: black, red and white, and nothing else.
 *
 * <p>Every renderer pulls its per-layer colour from here so the whole spectacle reads as one palette
 * — deep voids, blood reds and bone whites stacked in bands. Black is used as umbra and gaps rather
 * than as a glowing colour, since it barely shows against the darkened world; reds and whites carry
 * the visible geometry.
 */
public final class AbyssalPalette {

    // Red ramp, dim to incandescent.
    public static final int RED_0 = 0x2A0008;
    public static final int RED_1 = 0x55000E;
    public static final int RED_2 = 0x8A0016;
    public static final int RED_3 = 0xC01026;
    public static final int RED_4 = 0xFF2436;

    // White / bone ramp.
    public static final int WHITE_0 = 0xFFFFFF;
    public static final int WHITE_1 = 0xF2E9EC;
    public static final int WHITE_2 = 0xD6C7CD;

    // Void blacks (umbra).
    public static final int BLACK_0 = 0x070307;
    public static final int BLACK_1 = 0x0E0310;

    /** Banding sequence cycled by layer index to get the stacked black/red/white look. */
    private static final int[] SEQUENCE = {
            WHITE_0, RED_3, BLACK_0, RED_2, WHITE_1, RED_4, BLACK_1, RED_1, WHITE_2, RED_3,
    };

    private AbyssalPalette() {
    }

    public static int layer(int index) {
        return SEQUENCE[Math.floorMod(index, SEQUENCE.length)];
    }

    /** A red whose heat rises with {@code t} in [0,1]. */
    public static int redRamp(float t) {
        float h = Mth.clamp(t, 0.0F, 1.0F);
        int[] ramp = {RED_0, RED_1, RED_2, RED_3, RED_4};
        return ramp[Math.min(ramp.length - 1, (int) (h * ramp.length))];
    }

    public static int r(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    public static int g(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    public static int b(int rgb) {
        return rgb & 0xFF;
    }

    /** A breathing alpha in [min,255], offset per layer so bands shimmer out of phase. */
    public static int pulseAlpha(float timeTicks, int layerIndex, int min) {
        float pulse = 0.5F + 0.5F * Mth.sin(timeTicks * 0.08F + layerIndex * 0.7F);
        return Mth.clamp(min + (int) (pulse * (255 - min)), 0, 255);
    }
}
