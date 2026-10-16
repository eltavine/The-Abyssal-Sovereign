package com.eltavine.the_abyssal_sovereign;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Loader entry point, and deliberately nothing more.
 *
 * <p>The whole mod registers nothing with Minecraft: no entities, items, blocks, particles, sounds,
 * dimensions, config specs or network channels. This class exists only because the loader needs a
 * {@code @Mod} type to instantiate. All behaviour lives behind the client-only event subscribers and,
 * ultimately, the native daemon. On a dedicated server the subscribers never fire, so the mod simply
 * does nothing.
 */
@Mod(TheAbyssalSovereign.MODID)
public final class TheAbyssalSovereign {

    public static final String MODID = "the_abyssal_sovereign";

    public TheAbyssalSovereign(IEventBus modEventBus, ModContainer modContainer) {
        // Intentionally empty: client wiring is done by @EventBusSubscriber classes, and there is
        // nothing to register with the game.
    }
}
