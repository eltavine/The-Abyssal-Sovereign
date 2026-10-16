package com.eltavine.the_abyssal_sovereign.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

/**
 * The ground sigil: stacked roses, spirographs and Lissajous figures, and nothing simpler.
 *
 * <p>A ring, a disc or a regular polygon would read as a plain circle. Every layer here is a
 * self-intersecting curve — a rhodonea with at least three petals, a hypotrochoid whose radius
 * ratio is never 1, or a Lissajous figure whose two frequencies differ — drawn twice, once in each
 * direction, so the overlaps braid instead of sitting as a single stroke.
 */
final class MagicCircleRenderer {

    private static final int SAMPLES = 280;
    private static final int ROSE_LAYERS = 16;
    private static final int SPIRO_LAYERS = 12;
    private static final int LISSA_LAYERS = 10;
    private static final float GROUND_OFFSET = 0.2F;
    private static final float LAYER_LIFT = 0.045F;

    private MagicCircleRenderer() {
    }

    static void render(PoseStack poseStack, MultiBufferSource bufferSource, float age, float radius, float actionBoost) {
        VertexConsumer c = GeoDraw.glowBuffer(bufferSource);
        float heat = 1.0F + actionBoost * 0.35F;

        poseStack.pushPose();
        poseStack.translate(0.0F, GROUND_OFFSET, 0.0F);

        for (int i = 0; i < ROSE_LAYERS; i++) {
            lift(poseStack);
            int petals = 3 + (i % 9);
            float rr = radius * (0.18F + 0.05F * i) * heat;
            float dir = (i % 2 == 0) ? 1.0F : -1.0F;
            float rot = age * (0.15F + 0.02F * i) * dir * Mth.DEG_TO_RAD;
            float thickness = radius * (0.006F + 0.0015F * (i % 3));
            GeoDraw.rose(c, poseStack.last().pose(), rr, petals, SAMPLES, thickness,
                    AbyssalPalette.layer(i), AbyssalPalette.pulseAlpha(age, i, 150), rot);
            GeoDraw.rose(c, poseStack.last().pose(), rr * 0.62F, petals + 2, SAMPLES, thickness * 0.7F,
                    AbyssalPalette.layer(i + 3), AbyssalPalette.pulseAlpha(age, i + 4, 120), -rot * 1.4F);
        }

        for (int i = 0; i < SPIRO_LAYERS; i++) {
            lift(poseStack);
            float big = radius * (0.35F + 0.055F * i);
            float small = big * (0.22F + 0.03F * (i % 4));
            float d = small * (1.4F + 0.35F * (i % 3));
            int turns = 3 + (i % 5);
            float rot = age * (0.12F + 0.03F * i) * ((i % 2 == 0) ? 1.0F : -1.0F) * Mth.DEG_TO_RAD;
            GeoDraw.spiro(c, poseStack.last().pose(), big, small, d, turns, SAMPLES, radius * 0.005F,
                    AbyssalPalette.layer(i + 1), AbyssalPalette.pulseAlpha(age, i + 2, 140), rot);
        }

        for (int i = 0; i < LISSA_LAYERS; i++) {
            lift(poseStack);
            int a = 2 + (i % 5);
            int b = a + 1 + (i % 3);
            float amp = radius * (0.4F + 0.04F * i);
            float rot = -age * (0.2F + 0.02F * i) * Mth.DEG_TO_RAD;
            GeoDraw.lissajous(c, poseStack.last().pose(), amp, amp * 0.86F, a, b, i * 0.4F, SAMPLES,
                    radius * 0.0045F, AbyssalPalette.layer(i + 5), AbyssalPalette.pulseAlpha(age, i + 6, 130), rot);
        }

        poseStack.popPose();
    }

    private static void lift(PoseStack poseStack) {
        poseStack.translate(0.0F, LAYER_LIFT, 0.0F);
    }
}
