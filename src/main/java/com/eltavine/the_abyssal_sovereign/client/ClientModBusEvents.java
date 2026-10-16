package com.eltavine.the_abyssal_sovereign.client;

import com.eltavine.the_abyssal_sovereign.TheAbyssalSovereign;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Client mod-bus wiring: launch the daemon once the client is setting up.
 *
 * <p>Registered only on the client ({@code Dist.CLIENT}), so a dedicated server never touches the
 * daemon or the native binaries at all.
 */
@EventBusSubscriber(modid = TheAbyssalSovereign.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModBusEvents {

    private ClientModBusEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> SovereignClient.start(FMLPaths.CONFIGDIR.get()));
    }
}
