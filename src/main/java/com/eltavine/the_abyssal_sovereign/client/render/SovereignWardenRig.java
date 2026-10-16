package com.eltavine.the_abyssal_sovereign.client.render;

import com.eltavine.the_abyssal_sovereign.bridge.Protocol;
import net.minecraft.client.animation.definitions.WardenAnimation;
import net.minecraft.client.model.WardenModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.monster.warden.Warden;

/**
 * The boss model, driven without ever constructing an entity.
 *
 * <p>Reuses the vanilla Warden model and animations (the mod registers nothing and spawns nothing),
 * but drives them from self-owned {@link AnimationState}s instead of a {@link Warden} instance. This
 * is the whole reason the boss "cannot be removed by any means": it is not in the world, not in any
 * entity lookup, and never ticked by the game — it is a model posed from the daemon's commands.
 *
 * <p>{@code setupAnim} is deliberately not called: it reads fields off a {@link Warden} we do not
 * have. {@link #pose} reproduces the parts of it we need — head aim and the three combat animations —
 * through the protected {@code animate} hook, which needs no entity.
 */
public final class SovereignWardenRig extends WardenModel<Warden> {

    private final AnimationState attack = new AnimationState();
    private final AnimationState roar = new AnimationState();
    private final AnimationState sonicBoom = new AnimationState();
    private int currentAnim = Protocol.ANIM_IDLE;

    public SovereignWardenRig(ModelPart root) {
        super(root);
    }

    /** Switches animation when the daemon reports a new move, starting it on the client clock. */
    public void setAnim(int anim, int clientTick) {
        if (anim == currentAnim) {
            return;
        }
        currentAnim = anim;
        attack.stop();
        roar.stop();
        sonicBoom.stop();
        switch (anim) {
            case Protocol.ANIM_ATTACK -> attack.start(clientTick);
            case Protocol.ANIM_ROAR -> roar.start(clientTick);
            case Protocol.ANIM_SONIC_BOOM -> sonicBoom.start(clientTick);
            default -> {
            }
        }
    }

    /**
     * Poses the model for this frame.
     *
     * @param ageInTicks continuous client age (tick + partial), the same clock {@link #setAnim}
     *     started the states on
     */
    public void pose(float ageInTicks, float netHeadYaw, float headPitch) {
        this.root().getAllParts().forEach(ModelPart::resetPose);
        this.head.yRot = netHeadYaw * ((float) Math.PI / 180.0F);
        this.head.xRot = headPitch * ((float) Math.PI / 180.0F);
        this.animate(attack, WardenAnimation.WARDEN_ATTACK, ageInTicks);
        this.animate(roar, WardenAnimation.WARDEN_ROAR, ageInTicks);
        this.animate(sonicBoom, WardenAnimation.WARDEN_SONIC_BOOM, ageInTicks);
    }
}
