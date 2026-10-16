package com.eltavine.the_abyssal_sovereign.bridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Owns the daemon process and bridges it to the game thread without ever blocking it.
 *
 * <p>The game thread only ever calls {@link #submitSnapshot}, {@link #drainEffects},
 * {@link #bossState} and {@link #atmosphere} — all non-blocking. A dedicated IO thread does the
 * lock-step round trips with the daemon, so a slow or wedged daemon can never stall rendering.
 *
 * <p>Results are split the way the game thread consumes them: the latest boss transform and
 * atmosphere are retained (rendered every frame), while one-shot effects (damage, sounds,
 * particles, lightning, auras) are queued to be applied exactly once.
 */
public final class SovereignBridge {

    private static final Logger LOGGER = Logger.getLogger("AbyssalSovereign.Bridge");

    private static final BossCommand.BossState ABSENT =
            new BossCommand.BossState(false, 0, 0, 0, 0, 0, 0, Protocol.ANIM_IDLE, Long.MIN_VALUE, 0, Protocol.PHASE_DORMANT);

    private final AtomicReference<Snapshot> pending = new AtomicReference<>();
    private final AtomicReference<BossCommand.BossState> bossState = new AtomicReference<>(ABSENT);
    private final AtomicReference<BossCommand.Atmosphere> atmosphere =
            new AtomicReference<>(new BossCommand.Atmosphere(0, 0, 0));
    private final ConcurrentLinkedQueue<BossCommand> effects = new ConcurrentLinkedQueue<>();

    private volatile DaemonProcess daemon;
    private volatile Thread ioThread;
    private volatile boolean running;
    private volatile boolean ready;

    /**
     * Extracts and launches the daemon and completes the handshake.
     *
     * @return {@code true} if the daemon is up and ready, {@code false} if no binary is bundled for
     *     this host or it failed to start
     */
    public synchronized boolean start(DaemonConfig config) {
        if (ready) {
            return true;
        }
        try {
            Path exe = NativeDaemonBinaries.extract();
            if (exe == null) {
                LOGGER.warning("No native daemon bundled for host key " + NativeDaemonBinaries.hostKey());
                return false;
            }
            daemon = DaemonProcess.start(exe, config);
            running = true;
            ready = true;
            Thread thread = new Thread(this::ioLoop, "abyssal-sovereign-bridge");
            thread.setDaemon(true);
            thread.start();
            ioThread = thread;
            LOGGER.info("Abyssal Sovereign daemon started: " + exe);
            return true;
        } catch (IOException e) {
            LOGGER.severe("Failed to start Abyssal Sovereign daemon: " + e.getMessage());
            ready = false;
            running = false;
            return false;
        }
    }

    private void ioLoop() {
        while (running) {
            Snapshot snapshot = pending.getAndSet(null);
            if (snapshot == null) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            try {
                publish(daemon.exchange(snapshot));
            } catch (IOException e) {
                LOGGER.severe("Daemon exchange failed; bridge going down: " + e.getMessage());
                running = false;
                ready = false;
                bossState.set(ABSENT);
                break;
            }
        }
    }

    private void publish(Iterable<BossCommand> commands) {
        for (BossCommand command : commands) {
            if (command instanceof BossCommand.BossState state) {
                bossState.set(state);
            } else if (command instanceof BossCommand.Atmosphere atmo) {
                atmosphere.set(atmo);
            } else {
                effects.offer(command);
            }
        }
    }

    /** Hands the latest snapshot to the IO thread, replacing any not yet sent. */
    public void submitSnapshot(Snapshot snapshot) {
        pending.set(snapshot);
    }

    /** Applies and clears every queued one-shot effect. Call on the game thread. */
    public void drainEffects(Consumer<BossCommand> consumer) {
        BossCommand command;
        while ((command = effects.poll()) != null) {
            consumer.accept(command);
        }
    }

    public BossCommand.BossState bossState() {
        return bossState.get();
    }

    public BossCommand.Atmosphere atmosphere() {
        return atmosphere.get();
    }

    public boolean isReady() {
        DaemonProcess d = daemon;
        return ready && d != null && d.isAlive();
    }

    public synchronized void shutdown() {
        running = false;
        ready = false;
        Thread thread = ioThread;
        if (thread != null) {
            thread.interrupt();
        }
        DaemonProcess d = daemon;
        if (d != null) {
            d.close();
        }
        daemon = null;
        bossState.set(ABSENT);
        effects.clear();
    }
}
