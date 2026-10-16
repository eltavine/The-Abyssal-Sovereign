package com.eltavine.the_abyssal_sovereign.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * A towering cage of geometry wrapped around the Sovereign: dozens of stacked, counter-rotating star
 * rings climbing its full height, vertical ribs binding them, and three colossal gyroscope rings
 * wheeling through it. Black/red/white throughout. This is the "grand, uncommon geometry" layer — the
 * boss stands inside a cathedral of rotating shapes.
 */
final class GeometricHaloRenderer {

    private static final int RING_LAYERS = 30;
    private static final int RIBS = 12;

    private GeometricHaloRenderer() {
    }

    static void render(PoseStack poseStack, MultiBufferSource bufferSource, float age, float scale, float baseHeight) {
        VertexConsumer c = GeoDraw.glowBuffer(bufferSource);
        float topH = scale * baseHeight;
        float baseR = scale * 0.85F;

        stackedRings(c, poseStack, age, topH, baseR);
        verticalRibs(c, poseStack.last().pose(), age, topH, baseR);
        gyroscope(c, poseStack, age, topH);
    }

    private static void stackedRings(VertexConsumer c, PoseStack poseStack, float age, float topH, float baseR) {
        for (int i = 0; i < RING_LAYERS; i++) {
            float t = i / (float) (RING_LAYERS - 1);
            float y = topH * t;
            float profile = 0.72F + 0.5F * Mth.sin(Mth.PI * t); // bulges at mid-height
            float radius = baseR * profile;
            int sides = 3 + (i % 10);
            int step = (i % 3 == 0) ? Math.max(1, (sides - 1) / 2) : 1;
            float thickness = baseR * 0.02F;
            int color = AbyssalPalette.layer(i);
            int alpha = AbyssalPalette.pulseAlpha(age, i, 120);
            float dir = (i % 2 == 0) ? 1.0F : -1.0F;
            float rot = age * (0.4F + 0.05F * i) * dir;

            poseStack.pushPose();
            poseStack.translate(0.0F, y, 0.0F);
            GeoDraw.polygon(c, poseStack.last().pose(), radius, sides, step, thickness, color, alpha, rot);
            poseStack.popPose();
        }
    }

    private static void verticalRibs(VertexConsumer c, Matrix4f m, float age, float topH, float baseR) {
        float spin = age * 0.15F * Mth.DEG_TO_RAD;
        float width = baseR * 0.02F;
        for (int k = 0; k < RIBS; k++) {
            float a = spin + k * Mth.TWO_PI / RIBS;
            float ca = Mth.cos(a);
            float sa = Mth.sin(a);
            float x = ca * baseR;
            float z = sa * baseR;
            float px = -sa * width;
            float pz = ca * width;
            int color = (k % 2 == 0) ? AbyssalPalette.RED_3 : AbyssalPalette.WHITE_1;
            int alpha = AbyssalPalette.pulseAlpha(age, k * 3, 110);
            GeoDraw.vertex(c, m, x - px, 0.0F, z - pz, color, alpha);
            GeoDraw.vertex(c, m, x + px, 0.0F, z + pz, color, alpha);
            GeoDraw.vertex(c, m, x + px, topH, z + pz, color, alpha);
            GeoDraw.vertex(c, m, x - px, topH, z - pz, color, alpha);
        }
    }

    private static void gyroscope(VertexConsumer c, PoseStack poseStack, float age, float topH) {
        float midY = topH * 0.5F;
        float radius = topH * 0.52F;
        float thickness = radius * 0.03F;

        poseStack.pushPose();
        poseStack.translate(0.0F, midY, 0.0F);

        // Three great rings in orthogonal planes, each wheeling on its own axis.
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(age * 0.6F));
        GeoDraw.polygon(c, poseStack.last().pose(), radius, 96, 1, thickness, AbyssalPalette.WHITE_0,
                AbyssalPalette.pulseAlpha(age, 1, 150), 0.0F);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(age * 0.5F));
        GeoDraw.polygon(c, poseStack.last().pose(), radius * 0.92F, 96, 1, thickness, AbyssalPalette.RED_4,
                AbyssalPalette.pulseAlpha(age, 5, 150), 0.0F);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.mulPose(Axis.ZP.rotationDegrees(90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(-age * 0.45F));
        GeoDraw.polygon(c, poseStack.last().pose(), radius * 0.84F, 96, 1, thickness, AbyssalPalette.WHITE_2,
                AbyssalPalette.pulseAlpha(age, 9, 150), 0.0F);
        poseStack.popPose();

        poseStack.popPose();
    }
}
