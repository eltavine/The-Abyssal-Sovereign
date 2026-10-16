package com.eltavine.the_abyssal_sovereign.client;

import com.eltavine.the_abyssal_sovereign.bridge.Protocol;
import com.eltavine.the_abyssal_sovereign.bridge.Snapshot;
import com.eltavine.the_abyssal_sovereign.bridge.SovereignBridge;
import com.eltavine.the_abyssal_sovereign.client.effect.LocalPlayerEffector;
import com.eltavine.the_abyssal_sovereign.client.render.AbyssalLightningRenderer;
import com.eltavine.the_abyssal_sovereign.config.SovereignConfig;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The client-side orchestrator: the one place the game and the daemon meet.
 *
 * <p>Holds no behaviour of its own. Each client tick it observes the world, forwards a snapshot to
 * the daemon and drains whatever one-shot effects came back; rendering and the HUD read the latest
 * boss state straight off the bridge. "Invocation" just flips a flag that rides along in the
 * snapshot; the daemon decides everything that follows.
 */
public final class SovereignClient {

    private static final SovereignBridge BRIDGE = new SovereignBridge();

    private static volatile boolean active;
    private static volatile int clientTick;
    private static SovereignConfig config = SovereignConfig.defaults();
    private static boolean shutdownHookInstalled;

    private SovereignClient() {
    }

    /** Loads config and launches the daemon. Returns whether the daemon is ready. */
    public static synchronized boolean start(Path configDir) {
        config = SovereignConfig.loadOrCreate(configDir);
        boolean ready = BRIDGE.start(config.toDaemonConfig());
        if (ready && !shutdownHookInstalled) {
            Runtime.getRuntime().addShutdownHook(new Thread(BRIDGE::shutdown, "abyssal-sovereign-shutdown"));
            shutdownHookInstalled = true;
        }
        return ready;
    }

    /** Retries startup if the daemon is not already up (e.g. a transient first-launch failure). */
    public static synchronized boolean ensureStarted() {
        if (BRIDGE.isReady()) {
            return true;
        }
        return BRIDGE.start(config.toDaemonConfig());
    }

    public static SovereignBridge bridge() {
        return BRIDGE;
    }

    public static SovereignConfig config() {
        return config;
    }

    public static boolean isDaemonReady() {
        return BRIDGE.isReady();
    }

    public static int clientTick() {
        return clientTick;
    }

    /** Marks the Sovereign invoked for this session. There is no in-game way to undo this. */
    public static void invoke() {
        active = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void onWorldLeave() {
        active = false;
        AbyssalLightningRenderer.clear();
    }

    /** Per client tick: advance the clock, observe the world, feed the daemon, apply what returns. */
    public static void onClientTick() {
        clientTick++;
        if (!BRIDGE.isReady()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            // Not in a world: tell the daemon so it stands the boss down, and clear any backlog.
            BRIDGE.submitSnapshot(emptySnapshot());
            BRIDGE.drainEffects(command -> {
            });
            return;
        }

        Vec3 eye = player.getEyePosition();
        long dimHash = (level.dimension().location().toString().hashCode()) & 0xFFFFFFFFL;
        int flags = Protocol.FLAG_IN_WORLD | (active ? Protocol.FLAG_ACTIVE : 0);
        Snapshot snapshot = new Snapshot(
                clientTick,
                dimHash,
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                player.getHealth(), player.getMaxHealth(),
                eye.x, eye.y, eye.z,
                level.getGameTime(),
                flags);
        BRIDGE.submitSnapshot(snapshot);
        BRIDGE.drainEffects(LocalPlayerEffector::apply);
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot(clientTick, 0L, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
