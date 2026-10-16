package com.eltavine.the_abyssal_sovereign.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;

/**
 * The crown above the Sovereign's head: the reused glowstone core, a fan of dozens of red and white
 * beams, and stacked star-polygon halos. The rainbow beams of the old build are gone — everything
 * here is black/red/white now, and there are far more of them.
 */
final class AbyssalCrownRenderer {

    private static final float GLOWSTONE_SCALE = 70.0F;
    private static final int BEAM_HEIGHT = 11_000;
    private static final int BEAM_COUNT = 36;
    private static final float BEAM_RADIUS = 5.6F;
    private static final int HALO_LAYERS = 10;

    private AbyssalCrownRenderer() {
    }

    static void render(PoseStack poseStack, MultiBufferSource bufferSource, float age, long gameTime, float scale, float baseHeight) {
        float topY = baseHeight * scale + 220.0F;

        poseStack.pushPose();
        poseStack.translate(0.0D, topY, 0.0D);

        glowstoneCore(poseStack, bufferSource, age);
        beams(poseStack, bufferSource, age, gameTime);
        halos(poseStack, bufferSource, age);

        poseStack.popPose();
    }

    private static void glowstoneCore(PoseStack poseStack, MultiBufferSource bufferSource, float age) {
        BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(age * 5.2F));
        poseStack.mulPose(Axis.XP.rotationDegrees(6.0F + Mth.sin(age * 0.06F) * 10.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.cos(age * 0.04F) * 8.0F));
        float pulse = GLOWSTONE_SCALE * (0.9F + 0.1F * Mth.sin(age * 0.1F));
        poseStack.scale(pulse, pulse, pulse);
        poseStack.translate(-0.5D, 0.0D, -0.5D);
        blockRenderer.renderSingleBlock(
                Blocks.GLOWSTONE.defaultBlockState(), poseStack, bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    private static void beams(PoseStack poseStack, MultiBufferSource bufferSource, float age, long gameTime) {
        poseStack.pushPose();
        poseStack.translate(0.0D, 1.2D, 0.0D);
        for (int i = 0; i < BEAM_COUNT; i++) {
            poseStack.pushPose();
            float angle = age * 2.4F + (360.0F / BEAM_COUNT) * i;
            poseStack.mulPose(Axis.YP.rotationDegrees(angle));
            poseStack.translate(BEAM_RADIUS, 0.0D, 0.0D);
            poseStack.mulPose(Axis.ZP.rotationDegrees(18.0F + Mth.sin(age * 0.05F + i * 1.1F) * 14.0F));
            poseStack.mulPose(Axis.XP.rotationDegrees(Mth.cos(age * 0.04F + i * 1.7F) * 6.0F));

            int color = switch (i % 3) {
                case 0 -> 0xFF000000 | AbyssalPalette.WHITE_0;
                case 1 -> 0xFF000000 | AbyssalPalette.RED_4;
                default -> 0xFF000000 | AbyssalPalette.RED_2;
            };
            float outer = (i % 3 == 0) ? 3.0F : 2.0F;
            float inner = outer * 0.6F;
            BeaconRenderer.renderBeaconBeam(
                    poseStack, bufferSource, BeaconRenderer.BEAM_LOCATION, 1.0F, 1.0F,
                    gameTime + i * 7L, 0, -BEAM_HEIGHT, color, outer, inner);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static void halos(PoseStack poseStack, MultiBufferSource bufferSource, float age) {
        VertexConsumer c = GeoDraw.glowBuffer(bufferSource);
        poseStack.pushPose();
        // A stack of star-polygon crowns floating just above the core.
        for (int i = 0; i < HALO_LAYERS; i++) {
            poseStack.translate(0.0F, GLOWSTONE_SCALE * 0.18F, 0.0F);
            int sides = 5 + (i % 7);
            int step = (i % 2 == 0) ? Math.max(1, (sides - 1) / 2) : 1;
            float radius = GLOWSTONE_SCALE * (1.6F - 0.1F * i);
            float thickness = GLOWSTONE_SCALE * 0.05F;
            int color = AbyssalPalette.layer(i + 2);
            int alpha = AbyssalPalette.pulseAlpha(age, i, 140);
            float rot = age * (0.5F + 0.1F * i) * ((i % 2 == 0) ? 1.0F : -1.0F);
            GeoDraw.polygon(c, poseStack.last().pose(), radius, sides, step, thickness, color, alpha, rot);
        }
        poseStack.popPose();
    }
}
