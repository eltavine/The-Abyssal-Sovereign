package com.eltavine.the_abyssal_sovereign.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * The abyssal sigil: dozens of stacked black/red/white layers burning into the ground.
 *
 * <p>Built from filled annular bands, many nested star polygons (heptagrams, enneagrams and the like)
 * and toothed rings, each counter-rotating at its own rate. Drawn additively, so the overlapping
 * bands brighten into the layered glow rather than z-fighting as opaque coplanar quads.
 */
final class MagicCircleRenderer {

    private static final int SEGMENTS = 72;
    private static final int STAR_LAYERS = 24;
    private static final float GROUND_OFFSET = 0.15F;
    private static final float LAYER_LIFT = 0.03F;

    private MagicCircleRenderer() {
    }

    static void render(PoseStack poseStack, MultiBufferSource bufferSource, float age, float radius, float actionBoost) {
        VertexConsumer c = GeoDraw.glowBuffer(bufferSource);
        float heat = 0.4F + actionBoost * 0.6F;

        poseStack.pushPose();
        poseStack.translate(0.0F, GROUND_OFFSET, 0.0F);

        // Foundation: a dim red well under bright red and bone rims. (Additive, so the "black" of
        // the sigil is simply the dark ground showing between the glowing bands.)
        GeoDraw.band(c, poseStack.last().pose(), 0.0F, radius * 0.52F, SEGMENTS, AbyssalPalette.RED_0, 90);
        lift(poseStack);
        GeoDraw.band(c, poseStack.last().pose(), radius * 0.49F, radius * 0.52F, SEGMENTS, AbyssalPalette.RED_3, 235);
        lift(poseStack);
        GeoDraw.band(c, poseStack.last().pose(), radius * 0.30F, radius * 0.315F, SEGMENTS, AbyssalPalette.WHITE_1, 220);

        // Nested star polygons, convex and star-stepped, counter-rotating.
        for (int i = 0; i < STAR_LAYERS; i++) {
            lift(poseStack);
            float t = i / (float) STAR_LAYERS;
            int sides = 3 + (i % 10);
            int step = (i % 2 == 0) ? 1 : Math.max(1, (sides - 1) / 2);
            float rr = radius * (0.16F + 0.82F * t);
            float thickness = radius * (0.004F + 0.004F * (i % 3)) * (1.0F + heat);
            int color = AbyssalPalette.layer(i);
            int alpha = AbyssalPalette.pulseAlpha(age, i, 130);
            float dir = (i % 2 == 0) ? 1.0F : -1.0F;
            float rot = age * (0.25F + 0.06F * i) * dir * (0.6F + heat);
            GeoDraw.polygon(c, poseStack.last().pose(), rr, sides, step, thickness, color, alpha, rot);
        }

        // Outer toothed rings.
        lift(poseStack);
        GeoDraw.teeth(c, poseStack.last().pose(), radius * 0.88F, 48, radius * 0.06F, radius * 0.012F,
                AbyssalPalette.RED_4, AbyssalPalette.pulseAlpha(age, 3, 150), age * 0.4F);
        lift(poseStack);
        GeoDraw.teeth(c, poseStack.last().pose(), radius * 0.96F, 72, radius * 0.04F, radius * 0.008F,
                AbyssalPalette.WHITE_0, AbyssalPalette.pulseAlpha(age, 7, 150), -age * 0.3F);
        lift(poseStack);
        GeoDraw.polygon(c, poseStack.last().pose(), radius * 1.0F, 96, 1, radius * 0.01F,
                AbyssalPalette.RED_2, 210, 0.0F);

        poseStack.popPose();
    }

    private static void lift(PoseStack poseStack) {
        poseStack.translate(0.0F, LAYER_LIFT, 0.0F);
    }
}
