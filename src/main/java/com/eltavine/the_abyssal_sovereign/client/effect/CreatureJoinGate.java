package com.eltavine.the_abyssal_sovereign.client.effect;

import com.eltavine.the_abyssal_sovereign.TheAbyssalSovereign;
import com.eltavine.the_abyssal_sovereign.client.SovereignClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Refuses entry to every non-player creature while the Sovereign is invoked.
 *
 * <p>Both the client level and the integrated server post {@link EntityJoinLevelEvent} on this bus
 * before an entity is inserted — fresh spawns and creatures loaded from a chunk alike. Cancelling
 * the event means they never reach the lookup, the tick list or the save. Players are not creatures,
 * so joining and respawning are left alone. The subscriber is client-side; a dedicated server never
 * loads it, and the integrated server runs inside this same client.
 */
@EventBusSubscriber(modid = TheAbyssalSovereign.MODID, value = Dist.CLIENT)
public final class CreatureJoinGate {

    private CreatureJoinGate() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (CreatureCull.shouldBlockJoin(SovereignClient.isActive(), event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
