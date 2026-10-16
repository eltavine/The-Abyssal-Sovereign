package com.eltavine.the_abyssal_sovereign.client.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eltavine.the_abyssal_sovereign.client.guard.IntegrityRule.Finding;
import com.eltavine.the_abyssal_sovereign.client.guard.IntegrityRule.State;
import org.junit.jupiter.api.Test;

class IntegrityRuleTest {

    private static final int FRESH = 10;
    private static final int STALL = 30;
    private static final int BOTH = 300;

    /** A session that is in a world, invoked, past both grace windows, and drawing everything. */
    private static final class Signals {
        boolean inWorld = true;
        boolean worldSettled = true;
        boolean invokedSettled = true;
        boolean daemonReady = true;
        boolean bossPresent = true;
        boolean hooksAlive = true;
        boolean instrumentedProduction = false;
        int world = 0;
        int hud = 0;
        boolean windowActive = true;

        Finding eval() {
            return IntegrityRule.evaluate(inWorld, worldSettled, invokedSettled, daemonReady, bossPresent,
                    hooksAlive, instrumentedProduction, world, hud, windowActive, FRESH, STALL, BOTH);
        }
    }

    @Test
    void intactWhenEverythingDraws() {
        assertEquals(Finding.NONE, new Signals().eval());
    }

    @Test
    void judgesNothingOutsideAWorldOrBeforeGrace() {
        Signals outside = broken();
        outside.inWorld = false;
        assertEquals(Finding.NONE, outside.eval());

        Signals early = broken();
        early.worldSettled = false;
        assertEquals(Finding.NONE, early.eval());
    }

    @Test
    void justInvokedIsGivenGraceBeforeThePresenceIsJudged() {
        // Long in the world, invoked a moment ago: the daemon has not answered with the boss yet.
        Signals s = new Signals();
        s.invokedSettled = false;
        s.bossPresent = false;
        s.world = 100_000;
        s.hud = 100_000;
        assertEquals(Finding.NONE, s.eval());
    }

    @Test
    void aHookThatStoppedRunningIsAnAbnormalEnvironment() {
        Signals s = new Signals();
        s.hooksAlive = false;
        assertEquals(Finding.HOOKS_MISSING, s.eval());

        // Judged even before invocation.
        s.invokedSettled = false;
        assertEquals(Finding.HOOKS_MISSING, s.eval());
    }

    @Test
    void instrumentedShippedBuildIsAnAbnormalEnvironment() {
        Signals s = new Signals();
        s.instrumentedProduction = true;
        assertEquals(Finding.INSTRUMENTED, s.eval());
    }

    @Test
    void environmentOutranksTampering() {
        Signals s = new Signals();
        s.hooksAlive = false;
        s.bossPresent = false;
        assertEquals(Finding.HOOKS_MISSING, s.eval());
    }

    @Test
    void notInvokedSkipsThePresenceAndRenderChecks() {
        Signals s = new Signals();
        s.invokedSettled = false;
        s.bossPresent = false;
        s.daemonReady = false;
        s.world = 10_000;
        assertEquals(Finding.NONE, s.eval());
    }

    @Test
    void losingThePresenceIsTampering() {
        Signals deadBrain = new Signals();
        deadBrain.daemonReady = false;
        assertEquals(Finding.PRESENCE_LOST, deadBrain.eval());

        Signals hiddenBoss = new Signals();
        hiddenBoss.bossPresent = false;
        assertEquals(Finding.PRESENCE_LOST, hiddenBoss.eval());
    }

    @Test
    void worldRenderSuppressedWhileHudStillDrawsIsTampering() {
        Signals s = new Signals();
        s.world = STALL + 1;
        s.hud = 0;
        assertEquals(Finding.WORLD_RENDER_SUPPRESSED, s.eval());
    }

    @Test
    void hudSuppressedWhileWorldStillDrawsIsTampering() {
        Signals s = new Signals();
        s.hud = STALL + 1;
        s.world = 0;
        assertEquals(Finding.HUD_RENDER_SUPPRESSED, s.eval());
    }

    @Test
    void briefHitchOnOnePathIsTolerated() {
        Signals underStall = new Signals();
        underStall.world = STALL;
        assertEquals(Finding.NONE, underStall.eval());

        // One path stale but the other not clearly drawing either: not the differential signature.
        Signals neitherFresh = new Signals();
        neitherFresh.world = STALL + 1;
        neitherFresh.hud = FRESH + 1;
        assertEquals(Finding.NONE, neitherFresh.eval());
    }

    @Test
    void bothPathsStalledTogetherIsAPauseNotTampering() {
        Signals focused = new Signals();
        focused.world = BOTH;
        focused.hud = BOTH;
        assertEquals(Finding.NONE, focused.eval());

        Signals minimised = new Signals();
        minimised.windowActive = false;
        minimised.world = 100_000;
        minimised.hud = 100_000;
        assertEquals(Finding.NONE, minimised.eval());
    }

    @Test
    void bothPathsDarkWhileFocusedForTooLongIsTampering() {
        Signals s = new Signals();
        s.world = BOTH + 1;
        s.hud = BOTH + 1;
        assertEquals(Finding.ALL_RENDER_SUPPRESSED, s.eval());
    }

    @Test
    void everyFindingMapsToTheConsequenceItDemands() {
        assertEquals(State.OK, Finding.NONE.state());
        assertEquals(State.ABNORMAL_ENVIRONMENT, Finding.HOOKS_MISSING.state());
        assertEquals(State.ABNORMAL_ENVIRONMENT, Finding.INSTRUMENTED.state());
        assertEquals(State.ABNORMAL_ENVIRONMENT, Finding.TEST_EXIT.state());
        assertEquals(State.TAMPERED, Finding.PRESENCE_LOST.state());
        assertEquals(State.TAMPERED, Finding.WORLD_RENDER_SUPPRESSED.state());
        assertEquals(State.TAMPERED, Finding.HUD_RENDER_SUPPRESSED.state());
        assertEquals(State.TAMPERED, Finding.ALL_RENDER_SUPPRESSED.state());
        assertEquals(State.TAMPERED, Finding.TEST_LOCKDOWN.state());
    }

    @Test
    void verdictOnlyEverEscalates() {
        assertTrue(IntegrityRule.isUpgrade(Finding.NONE, Finding.WORLD_RENDER_SUPPRESSED));
        assertTrue(IntegrityRule.isUpgrade(Finding.TEST_LOCKDOWN, Finding.HOOKS_MISSING));
        // A lockdown never clears itself, and an armed exit is never downgraded to a lockdown.
        assertFalse(IntegrityRule.isUpgrade(Finding.PRESENCE_LOST, Finding.NONE));
        assertFalse(IntegrityRule.isUpgrade(Finding.TEST_EXIT, Finding.PRESENCE_LOST));
        assertFalse(IntegrityRule.isUpgrade(Finding.PRESENCE_LOST, Finding.HUD_RENDER_SUPPRESSED));
    }

    @Test
    void aRealFindingReplacesASimulatedOneSoItCannotBeResetAway() {
        assertTrue(IntegrityRule.isUpgrade(Finding.TEST_LOCKDOWN, Finding.PRESENCE_LOST));
        assertTrue(IntegrityRule.isUpgrade(Finding.TEST_EXIT, Finding.HOOKS_MISSING));
        assertFalse(IntegrityRule.isUpgrade(Finding.PRESENCE_LOST, Finding.TEST_LOCKDOWN));
        assertFalse(IntegrityRule.isUpgrade(Finding.TEST_LOCKDOWN, Finding.TEST_LOCKDOWN));
    }

    @Test
    void onlyTheTestFindingsAreWithdrawable() {
        for (Finding f : Finding.values()) {
            assertEquals(f == Finding.TEST_LOCKDOWN || f == Finding.TEST_EXIT, f.isTest(), f.name());
        }
    }

    @Test
    void launcherLoginAgentsAreNotInstrumentation() {
        assertFalse(IntegrityRule.isInstrumented(java.util.List.of("-Xmx4G", "-Dlog4j2.formatMsgNoLookups=true")));
        assertFalse(IntegrityRule.isInstrumented(java.util.List.of(
                "-javaagent:C:\\HMCL\\authlib-injector-1.2.5.jar=https://littleskin.cn/api/yggdrasil")));
        assertFalse(IntegrityRule.isInstrumented(java.util.List.of("-javaagent:/opt/mc/nide8auth.jar=abc")));
    }

    @Test
    void unknownAgentsAndDebuggersAreInstrumentation() {
        assertTrue(IntegrityRule.isInstrumented(java.util.List.of("-javaagent:/tmp/patcher.jar")));
        assertTrue(IntegrityRule.isInstrumented(java.util.List.of("-agentlib:jdwp=transport=dt_socket,server=y")));
        assertTrue(IntegrityRule.isInstrumented(java.util.List.of("-agentpath:/usr/lib/libhook.so")));
    }

    /** Every runtime check failing at once, to prove the outer gates hold on their own. */
    private static Signals broken() {
        Signals s = new Signals();
        s.daemonReady = false;
        s.bossPresent = false;
        s.hooksAlive = false;
        s.instrumentedProduction = true;
        s.world = 100_000;
        s.hud = 100_000;
        return s;
    }
}
