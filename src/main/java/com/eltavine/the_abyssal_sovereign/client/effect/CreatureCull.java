package com.eltavine.the_abyssal_sovereign.client.effect;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * Who the Sovereign is allowed to erase.
 *
 * <p>A creature is any {@link LivingEntity} that is not a {@link Player} and not a multipart
 * <em>part</em>. The parent (an ender dragon, for example) is a creature and is erased; its parts
 * are not stored on their own and are dropped with the parent. The decision itself lives in
 * {@link CreatureCullRule}, which a unit test locks without booting the game.
 */
public final class CreatureCull {

    private CreatureCull() {
    }

    public static boolean appliesTo(Entity entity) {
        return entity != null
                && CreatureCullRule.appliesTo(entity instanceof LivingEntity, entity instanceof Player, entity instanceof PartEntity<?>);
    }

    /** Join is refused only while the Sovereign is invoked, and only for a creature. */
    public static boolean shouldBlockJoin(boolean sovereignActive, Entity entity) {
        return sovereignActive && appliesTo(entity);
    }
}
