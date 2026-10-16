package com.eltavine.the_abyssal_sovereign.client.render;

import com.eltavine.the_abyssal_sovereign.bridge.BossCommand;
import com.eltavine.the_abyssal_sovereign.bridge.Protocol;
import com.eltavine.the_abyssal_sovereign.client.SovereignClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Renders the Sovereign into the world from the daemon's latest boss state, with no entity involved.
 *
 * <p>Hooked at {@link RenderLevelStageEvent.Stage#AFTER_ENTITIES}. The transform mirrors
 * {@code LivingEntityRenderer} exactly (the facing rotation, the {@code (-1,-1,1)} flip, the boss
 * scale, then the {@code -1.501} foot offset) so the reused Warden model lands where a real entity
 * would, then the sigil, light crown and lightning are layered on in plain block space.
 */
public final class SovereignWorldRenderer {

    private static final ResourceLocation WARDEN_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/warden/warden.png");

    /** Vanilla Warden bounding-box height, used to place the light crown above the head. */
    private static final float BASE_HEIGHT = 2.9F;

    private static SovereignWardenRig rig;

    private SovereignWorldRenderer() {
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        BossCommand.BossState state = SovereignClient.bridge().bossState();
        if (!state.present()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        int clientTick = SovereignClient.clientTick();
        float age = clientTick + partial;
        long gameTime = minecraft.level.getGameTime();

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        SovereignWardenRig model = rig(minecraft);
        model.setAnim(state.anim(), clientTick);

        // The towering model itself.
        poseStack.pushPose();
        poseStack.translate(state.x() - camPos.x, state.y() - camPos.y, state.z() - camPos.z);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.yaw()));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.scale(state.scale(), state.scale(), state.scale());
        poseStack.translate(0.0F, -1.501F, 0.0F);
        model.pose(age, 0.0F, state.pitch());
        model.renderToBuffer(
                poseStack,
                buffers.getBuffer(RenderType.entityCutoutNoCull(WARDEN_TEXTURE)),
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF);
        poseStack.popPose();

        // The layered black/red/white geometry, all in plain block space at the anchor:
        // the ground sigil, the towering cage around the body, and the crown above the head.
        float actionBoost = state.anim() == Protocol.ANIM_IDLE ? 0.0F : 1.0F;
        MagicCircleRenderer.render(poseStack, buffers, age, state.scale() * 0.9F, actionBoost);
        GeometricHaloRenderer.render(poseStack, buffers, age, state.scale(), BASE_HEIGHT);
        AbyssalCrownRenderer.render(poseStack, buffers, age, gameTime, state.scale(), BASE_HEIGHT);
        poseStack.popPose();

        // Judgement lightning is already in camera-relative world coordinates.
        AbyssalLightningRenderer.render(poseStack, buffers, camPos, clientTick, partial);

        buffers.endBatch();
    }

    private static SovereignWardenRig rig(Minecraft minecraft) {
        if (rig == null) {
            rig = new SovereignWardenRig(minecraft.getEntityModels().bakeLayer(ModelLayers.WARDEN));
        }
        return rig;
    }
}
