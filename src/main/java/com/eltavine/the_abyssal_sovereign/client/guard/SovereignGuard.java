package com.eltavine.the_abyssal_sovereign.client.guard;

import com.eltavine.the_abyssal_sovereign.client.SovereignClient;
import com.eltavine.the_abyssal_sovereign.client.guard.IntegrityRule.Finding;
import com.eltavine.the_abyssal_sovereign.client.guard.IntegrityRule.State;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.logging.Logger;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.fml.loading.FMLLoader;

/**
 * The self-check that keeps the Sovereign's rendering and presence honest, and the enforcement when
 * they are not. It owns no behaviour of the boss; it only watches that the boss is still being drawn
 * and still has a brain, and reacts when something tears that away.
 *
 * <ul>
 *   <li><b>Two drivers, one clock.</b> The guard is judged from a mixin at the head of every client
 *       tick and from the client-tick event at its end, and keeps its own clock that either one can
 *       advance. Unregistering the mod's event subscribers therefore neither silences the guard nor
 *       freezes the clock it measures staleness with: the world render goes dark while the HUD mixin
 *       keeps drawing, which is the one-sided stall the rule calls tampering.</li>
 *   <li><b>Heartbeats.</b> The world render, the HUD render and the mixins stamp that clock as they
 *       run; how stale the stamps are goes, with the bridge and the environment, to
 *       {@link IntegrityRule}.</li>
 *   <li><b>Lockdown.</b> A tamper verdict latches an anti-cheat style full-screen red overlay
 *       ({@link #renderOverlay}) over the HUD and over every screen. It never clears on its own.</li>
 *   <li><b>Exit.</b> An abnormal-environment verdict starts a ten-second countdown. A clean quit is
 *       requested three seconds before the deadline so a singleplayer world can save; an off-thread
 *       watchdog halts the JVM at the deadline whether or not the client is still ticking.</li>
 * </ul>
 */
public final class SovereignGuard {

    private static final Logger LOGGER = Logger.getLogger("AbyssalSovereign.Guard");

    /** Ticks before a verdict is trusted, covering world load and the daemon's first reply. */
    private static final int GRACE_TICKS = 60;
    /** At or under this many ticks stale, a render path is actively drawing. */
    private static final int FRESH_LIMIT = 10;
    /** Over this many ticks stale, a single render path counts as suppressed (~1.5s). */
    private static final int STALL_LIMIT = 30;
    /** Over this many ticks stale on both paths while focused counts as suppressed (~15s). */
    private static final int BOTH_STALL_LIMIT = 300;
    private static final int NEVER = Integer.MIN_VALUE;
    /**
     * Real time in a world, window focused, before a never-seen HUD mixin counts as stripped. Measured
     * on the wall clock, not in ticks: {@code runTick} runs up to ten catch-up ticks per rendered
     * frame, so a tick count can outrun the render-driven mixin latch during a world-load hitch.
     */
    private static final long HOOK_GRACE_MILLIS = 5_000L;

    private static final long EXIT_COUNTDOWN_MILLIS = 10_000L;
    private static final long CLEAN_QUIT_LEAD_MILLIS = 3_000L;
    private static final int EXIT_CODE = 0x5A;

    /** JVM input arguments cannot change at runtime, so this half of the environment is read once. */
    private static final boolean INSTRUMENTED_PRODUCTION = detectInstrumentedProduction();

    /** Advanced at the head of every client tick by {@code MinecraftTickMixin}. */
    private static int mixinTicks;
    /** Advanced at the end of every client tick by the event-bus subscriber. */
    private static int pumpTicks;
    /** The clock value last judged, so the two drivers in one tick judge it once. */
    private static int judgedClock;

    private static volatile int lastWorldRenderTick = NEVER;
    private static volatile int lastHudRenderTick = NEVER;
    private static volatile boolean guiMixinSeen;

    private static ClientLevel lastLevel;
    private static int inWorldTicks;
    /** Ticks invoked and in the same level, so the boss has time to reappear after a world change. */
    private static int invokedTicks;
    /** When the current level was entered; 0 while out of a world. */
    private static long inWorldSinceMillis;

    private static volatile Finding finding = Finding.NONE;
    /** Bumped on every arm and every test reset; a watchdog only ever fires for its own generation. */
    private static volatile int exitGeneration;
    private static volatile long exitDeadlineMillis;
    private static boolean cleanQuitRequested;

    private SovereignGuard() {
    }

    // --- Heartbeats, stamped from the render hooks and mixins. ---------------------------------

    public static void markWorldRender() {
        lastWorldRenderTick = clock();
    }

    public static void markHudRender() {
        lastHudRenderTick = clock();
    }

    public static void markGuiMixin() {
        guiMixinSeen = true;
    }

    // --- Drivers and the guard's own clock. ----------------------------------------------------

    /** Driver one: {@code MinecraftTickMixin}, at the head of every client tick. */
    public static void onTickMixin() {
        mixinTicks++;
        judgeOnce();
    }

    /** Driver two: the client-tick event at the end of every tick, for when the mixin is absent. */
    public static void onEventPump() {
        pumpTicks++;
        judgeOnce();
    }

    /** Whichever driver is still alive keeps this moving, so removing one cannot freeze the clock. */
    private static int clock() {
        return Math.max(mixinTicks, pumpTicks);
    }

    private static void judgeOnce() {
        int now = clock();
        if (now == judgedClock) {
            return;
        }
        judgedClock = now;
        judge(now);
    }

    // --- Per-tick judgement. -------------------------------------------------------------------

    private static void judge(int now) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean inWorld = minecraft.level != null && minecraft.player != null;
        if (minecraft.level != lastLevel) {
            lastLevel = minecraft.level;
            inWorldTicks = 0;
            invokedTicks = 0;
            inWorldSinceMillis = inWorld ? System.currentTimeMillis() : 0L;
        }
        inWorldTicks = inWorld ? Math.min(inWorldTicks + 1, GRACE_TICKS + 1) : 0;
        invokedTicks = inWorld && SovereignClient.isActive() ? Math.min(invokedTicks + 1, GRACE_TICKS + 1) : 0;
        if (!inWorld) {
            inWorldSinceMillis = 0L;
        } else if (inWorldSinceMillis == 0L) {
            inWorldSinceMillis = System.currentTimeMillis();
        }

        boolean windowActive = minecraft.isWindowActive();
        // The HUD mixin fires on every rendered frame in a world, so after real time in one with the
        // window focused it has fired unless it was stripped. Until then, or while unfocused, it is
        // given the benefit of the doubt rather than judged on frames that never had a chance to draw.
        boolean hookGraceOver = inWorldSinceMillis != 0L
                && System.currentTimeMillis() - inWorldSinceMillis > HOOK_GRACE_MILLIS;
        boolean hooksAlive = guiMixinSeen || !hookGraceOver || !windowActive;

        raise(IntegrityRule.evaluate(
                inWorld,
                inWorldTicks > GRACE_TICKS,
                invokedTicks > GRACE_TICKS,
                SovereignClient.bridge().isReady(),
                SovereignClient.bridge().bossState().present(),
                hooksAlive,
                INSTRUMENTED_PRODUCTION,
                ticksSince(now, lastWorldRenderTick),
                ticksSince(now, lastHudRenderTick),
                windowActive,
                FRESH_LIMIT,
                STALL_LIMIT,
                BOTH_STALL_LIMIT));

        long deadline = exitDeadlineMillis;
        if (deadline != 0L && !cleanQuitRequested
                && System.currentTimeMillis() >= deadline - CLEAN_QUIT_LEAD_MILLIS) {
            cleanQuitRequested = true;
            LOGGER.severe("Abnormal mod environment: requesting a clean quit ahead of the deadline.");
            minecraft.stop();
        }
    }

    private static int ticksSince(int now, int last) {
        return last == NEVER ? Integer.MAX_VALUE / 2 : Math.max(0, now - last);
    }

    /** Latches a stricter finding, and arms the exit once the verdict turns abnormal. */
    private static synchronized void raise(Finding verdict) {
        if (IntegrityRule.isUpgrade(finding, verdict)) {
            finding = verdict;
            LOGGER.severe("Integrity verdict: " + verdict + " [" + verdict.code() + "]");
        }
        if (finding.state() == State.ABNORMAL_ENVIRONMENT && exitDeadlineMillis == 0L) {
            armExit();
        }
    }

    // --- Enforcement: the ten-second watchdog. -------------------------------------------------

    /** Called under the class lock from {@link #raise}. */
    private static void armExit() {
        int generation = ++exitGeneration;
        long deadline = System.currentTimeMillis() + EXIT_COUNTDOWN_MILLIS;
        exitDeadlineMillis = deadline;
        cleanQuitRequested = false;
        LOGGER.severe("Abnormal mod environment: Minecraft will exit within "
                + EXIT_COUNTDOWN_MILLIS / 1000L + " seconds.");
        Thread watchdog = new Thread(() -> runExit(generation, deadline), "abyssal-sovereign-enforcer");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void runExit(int generation, long deadline) {
        long remaining;
        while ((remaining = deadline - System.currentTimeMillis()) > 0L) {
            if (exitGeneration != generation) {
                return;
            }
            try {
                Thread.sleep(Math.min(remaining, 50L));
            } catch (InterruptedException ignored) {
                // An interrupt does not buy time.
            }
        }
        if (exitGeneration != generation) {
            return;
        }
        // The daemon ends on its own when its stdin closes with the JVM, so nothing is orphaned.
        LOGGER.severe("Abnormal mod environment: deadline reached, halting.");
        Runtime.getRuntime().halt(EXIT_CODE);
    }

    // --- Test hooks, invoked from the chat test commands. --------------------------------------

    public static void triggerTamperTest() {
        raise(Finding.TEST_LOCKDOWN);
    }

    public static void triggerExitTest() {
        raise(Finding.TEST_EXIT);
    }

    /**
     * Withdraws a verdict the test command raised, together with its pending exit. A verdict the
     * guard reached on its own is not withdrawable: the chat line is not a way out of a real lockdown.
     */
    public static synchronized boolean resetTest() {
        if (!finding.isTest()) {
            return false;
        }
        exitGeneration++;
        exitDeadlineMillis = 0L;
        cleanQuitRequested = false;
        finding = Finding.NONE;
        LOGGER.info("Integrity guard: test verdict withdrawn.");
        return true;
    }

    // --- Overlay, drawn last over the HUD and over every screen. --------------------------------

    public static void renderOverlay(GuiGraphics graphics) {
        Finding current = finding;
        if (current.state() == State.OK) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float pulse = 0.5F + 0.5F * (float) Math.sin(Util.getMillis() * 0.006D);
        if (current.state() == State.ABNORMAL_ENVIRONMENT) {
            renderAbnormal(graphics, font, width, height, pulse, current);
        } else {
            renderTampered(graphics, font, width, height, pulse, current);
        }
    }

    private static void renderTampered(GuiGraphics g, Font font, int w, int h, float pulse, Finding f) {
        g.fill(0, 0, w, h, argb(215 + (int) (pulse * 40.0F), 150 + (int) (pulse * 70.0F), 6, 8));
        drawBorder(g, w, h, argb(255, 255, 60 + (int) (pulse * 80.0F), 60));
        int cx = w / 2;
        int cy = h / 2;
        drawCentered(g, font, Component.translatable("guard.the_abyssal_sovereign.lockdown_title"),
                cx, cy - 40, 2.4F, 0xFFFFFFFF);
        drawCentered(g, font, Component.translatable("guard.the_abyssal_sovereign.lockdown_line1"),
                cx, cy, 1.1F, 0xFFFFE6E6);
        drawCentered(g, font, Component.translatable("guard.the_abyssal_sovereign.lockdown_line2"),
                cx, cy + 16, 1.1F, 0xFFFFE6E6);
        drawCentered(g, font, Component.translatable("guard.the_abyssal_sovereign.code", f.code()),
                cx, cy + 40, 1.0F, 0xFF2A0000);
    }

    private static void renderAbnormal(GuiGraphics g, Font font, int w, int h, float pulse, Finding f) {
        g.fill(0, 0, w, h, argb(240, 12, 0, 0));
        g.fill(0, 0, w, h, argb(60 + (int) (pulse * 70.0F), 170, 0, 0));
        drawBorder(g, w, h, argb(255, 200 + (int) (pulse * 55.0F), 24, 24));
        int cx = w / 2;
        int cy = h / 2;
        long remaining = Math.max(0L, exitDeadlineMillis - System.currentTimeMillis());
        int seconds = (int) ((remaining + 999L) / 1000L);
        drawCentered(g, font, Component.translatable("guard.the_abyssal_sovereign.abnormal_title"),
                cx, cy - 52, 2.2F, 0xFFFF5050);
        drawCentered(g, font, Component.translatable("guard.the_abyssal_sovereign.abnormal_line1"),
                cx, cy - 14, 1.1F, 0xFFFFD6D6);
        drawCentered(g, font, Component.translatable("guard.the_abyssal_sovereign.abnormal_countdown", seconds),
                cx, cy + 6, 2.6F, 0xFFFFFFFF);
        drawCentered(g, font, Component.translatable("guard.the_abyssal_sovereign.code", f.code()),
                cx, cy + 48, 1.0F, 0xFFB04040);
    }

    private static void drawBorder(GuiGraphics g, int w, int h, int color) {
        int b = Math.max(3, Math.min(w, h) / 90);
        g.fill(0, 0, w, b, color);
        g.fill(0, h - b, w, h, color);
        g.fill(0, 0, b, h, color);
        g.fill(w - b, 0, w, h, color);
    }

    /** Centred, scaled text, shrunk to fit the screen width so no language or GUI scale overflows. */
    private static void drawCentered(GuiGraphics g, Font font, Component text, int cx, int cy,
            float scale, int color) {
        int textWidth = Math.max(1, font.width(text));
        float fitted = Math.min(scale, (g.guiWidth() - 24.0F) / textWidth);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0F);
        g.pose().scale(fitted, fitted, 1.0F);
        g.drawString(font, text, -textWidth / 2, -font.lineHeight / 2, color, true);
        g.pose().popPose();
    }

    private static int argb(int a, int r, int gr, int b) {
        return Mth.clamp(a, 0, 255) << 24 | Mth.clamp(r, 0, 255) << 16 | Mth.clamp(gr, 0, 255) << 8 | Mth.clamp(b, 0, 255);
    }

    private static boolean detectInstrumentedProduction() {
        try {
            if (!FMLLoader.isProduction()) {
                return false;
            }
            List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            return IntegrityRule.isInstrumented(args);
        } catch (Throwable t) {
            LOGGER.warning("Could not inspect the JVM environment: " + t);
            return false;
        }
    }
}
