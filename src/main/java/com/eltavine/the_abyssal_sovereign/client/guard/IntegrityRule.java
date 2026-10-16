package com.eltavine.the_abyssal_sovereign.client.guard;

import java.util.Locale;

/**
 * The integrity decision with no Minecraft types, so a unit test can lock it without booting the
 * game. {@link SovereignGuard} gathers the live signals and drives the consequences; this class only
 * decides which finding those signals describe.
 *
 * <p>The render signals are counted in <em>client ticks since the hook last ran</em>, not wall-clock
 * time, and that is the property the whole check leans on. A genuine stall — minimised window, a
 * breakpoint, a chunk-gen freeze — pauses the client tick and the render together, so the counters do
 * not grow. They only grow when the client keeps ticking while a render path is specifically
 * suppressed, which is exactly the tampering this is meant to catch.
 */
public final class IntegrityRule {

    private IntegrityRule() {
    }

    public enum State {
        /** Everything the Sovereign needs to manifest is intact. */
        OK,
        /** The rendering or the Titan's presence is being suppressed at runtime: red lockdown. */
        TAMPERED,
        /** The mod environment itself is wrong — our hooks are gone or the JVM is instrumented: forced exit. */
        ABNORMAL_ENVIRONMENT
    }

    /** What tripped, with the code shown on the lockdown screen. */
    public enum Finding {
        NONE(State.OK, "OK"),
        HOOKS_MISSING(State.ABNORMAL_ENVIRONMENT, "E-01"),
        INSTRUMENTED(State.ABNORMAL_ENVIRONMENT, "E-02"),
        PRESENCE_LOST(State.TAMPERED, "T-01"),
        WORLD_RENDER_SUPPRESSED(State.TAMPERED, "T-02"),
        HUD_RENDER_SUPPRESSED(State.TAMPERED, "T-03"),
        ALL_RENDER_SUPPRESSED(State.TAMPERED, "T-04"),
        TEST_LOCKDOWN(State.TAMPERED, "X-01"),
        TEST_EXIT(State.ABNORMAL_ENVIRONMENT, "X-02");

        private final State state;
        private final String code;

        Finding(State state, String code) {
            this.state = state;
            this.code = code;
        }

        public State state() {
            return state;
        }

        public String code() {
            return code;
        }

        /** Raised by the test command rather than detected; only these may be withdrawn. */
        public boolean isTest() {
            return this == TEST_LOCKDOWN || this == TEST_EXIT;
        }
    }

    /**
     * Agents a stock or popular third-party launcher injects for login and skins. They say nothing
     * about this mod, and treating them as abnormal would force-exit every player who uses them.
     */
    private static final String[] LAUNCHER_AGENTS = {"authlib-injector", "nide8auth", "log4j"};

    /**
     * @param inWorld                a client level and player are present
     * @param worldSettled           in a world past the grace window: the environment may be judged
     * @param invokedSettled         invoked and past the grace window since invocation (and since the
     *                               last world change): the presence and renders may be judged
     * @param daemonReady            the native bridge is up and answering
     * @param bossPresent            the daemon is reporting the boss as present
     * @param hooksAlive             every mixin and the event-bus pump is still running
     * @param instrumentedProduction a shipped build running under a JVM agent / debugger
     * @param ticksSinceWorldRender  client ticks since the in-world Titan render last ran
     * @param ticksSinceHudRender    client ticks since the HUD render last ran
     * @param windowActive           the game window is focused (not minimised/alt-tabbed)
     * @param freshLimit             at or below this, a render path counts as actively drawing
     * @param stallLimit             above this, a single render path counts as suppressed
     * @param bothStallLimit         above this, both paths suppressed at once counts as suppressed
     */
    public static Finding evaluate(
            boolean inWorld,
            boolean worldSettled,
            boolean invokedSettled,
            boolean daemonReady,
            boolean bossPresent,
            boolean hooksAlive,
            boolean instrumentedProduction,
            int ticksSinceWorldRender,
            int ticksSinceHudRender,
            boolean windowActive,
            int freshLimit,
            int stallLimit,
            int bothStallLimit) {

        if (!inWorld || !worldSettled) {
            return Finding.NONE;
        }

        // The environment is judged independent of invocation: once we have been in a world past the
        // grace window every hook must be running, and a shipped build must not be instrumented.
        // Losing a hook outranks everything below, since a removed hook also darkens what it drove.
        if (!hooksAlive) {
            return Finding.HOOKS_MISSING;
        }
        if (instrumentedProduction) {
            return Finding.INSTRUMENTED;
        }

        // Not judged until the daemon has had time to answer the invocation: right after it, or right
        // after a world change, the boss is legitimately not reported yet.
        if (!invokedSettled) {
            return Finding.NONE;
        }

        // The presence itself is gone: the brain died or the boss state was suppressed.
        if (!daemonReady || !bossPresent) {
            return Finding.PRESENCE_LOST;
        }

        boolean worldStale = ticksSinceWorldRender > stallLimit;
        boolean hudStale = ticksSinceHudRender > stallLimit;
        boolean worldFresh = ticksSinceWorldRender <= freshLimit;
        boolean hudFresh = ticksSinceHudRender <= freshLimit;

        // One path suppressed while the other still draws is unambiguous: a real pause stalls both.
        if (worldStale && hudFresh) {
            return Finding.WORLD_RENDER_SUPPRESSED;
        }
        if (hudStale && worldFresh) {
            return Finding.HUD_RENDER_SUPPRESSED;
        }

        // Both paths dark while the window is focused and the client is still ticking: an attack that
        // disabled every render path at once rather than a minimised or frozen client.
        if (windowActive
                && ticksSinceWorldRender > bothStallLimit
                && ticksSinceHudRender > bothStallLimit) {
            return Finding.ALL_RENDER_SUPPRESSED;
        }

        return Finding.NONE;
    }

    /**
     * True when {@code next} should replace {@code current}: it is stricter, or it is a real finding
     * of the same severity as a simulated one, so a test verdict can never mask a real one and then be
     * withdrawn with it.
     */
    public static boolean isUpgrade(Finding current, Finding next) {
        int now = severity(current.state());
        int then = severity(next.state());
        if (then != now) {
            return then > now;
        }
        return current.isTest() && !next.isTest();
    }

    /** Whether the JVM's input arguments show an agent or debugger that no launcher accounts for. */
    public static boolean isInstrumented(Iterable<String> jvmArguments) {
        for (String argument : jvmArguments) {
            String lower = argument.toLowerCase(Locale.ROOT);
            boolean agent = lower.startsWith("-javaagent")
                    || lower.startsWith("-agentpath")
                    || lower.startsWith("-agentlib");
            if (agent && !isLauncherAgent(lower)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLauncherAgent(String lowerArgument) {
        for (String name : LAUNCHER_AGENTS) {
            if (lowerArgument.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static int severity(State state) {
        return switch (state) {
            case OK -> 0;
            case TAMPERED -> 1;
            case ABNORMAL_ENVIRONMENT -> 2;
        };
    }
}
