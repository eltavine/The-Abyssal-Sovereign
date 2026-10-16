package com.eltavine.the_abyssal_sovereign.client.effect;

/**
 * The cull decision with no Minecraft types, so a unit test can lock it without booting the game.
 *
 * <p>A target is living, is not a player, and is not a multipart part. The part's parent is judged
 * on its own and, when it qualifies, takes the parts with it.
 */
public final class CreatureCullRule {

    private CreatureCullRule() {
    }

    public static boolean appliesTo(boolean living, boolean player, boolean part) {
        return living && !player && !part;
    }

    public static boolean shouldBlockJoin(boolean sovereignActive, boolean living, boolean player, boolean part) {
        return sovereignActive && appliesTo(living, player, part);
    }
}
