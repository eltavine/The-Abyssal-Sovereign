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

    /** A per-tick jump longer than this is a teleport or respawn, drawn as a cut rather than a sweep. */
    private static final double SNAP_DISTANCE_SQR = 8.0D * 8.0D;

    private static SovereignWardenRig rig;
    private static Vec3 prevAnchor;
    private static Vec3 anchor;

    private SovereignWorldRenderer() {
    }

    /**
     * Steps the interpolation window to the daemon's latest anchor; call once per client tick. The
     * anchor only moves on ticks while the camera moves every frame, so it is drawn interpolated,
     * the way vanilla draws an entity.
     */
    public static void tick() {
        BossCommand.BossState state = SovereignClient.bridge().bossState();
        if (!state.present()) {
            prevAnchor = null;
            anchor = null;
            return;
        }
        Vec3 latest = new Vec3(state.x(), state.y(), state.z());
        prevAnchor = anchor == null || anchor.distanceToSqr(latest) > SNAP_DISTANCE_SQR ? latest : anchor;
        anchor = latest;
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
        Vec3 pos = anchor == null ? new Vec3(state.x(), state.y(), state.z()) : prevAnchor.lerp(anchor, partial);

        // The towering model itself.
        poseStack.pushPose();
        poseStack.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z);
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
