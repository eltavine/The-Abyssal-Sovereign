package com.eltavine.the_abyssal_sovereign.client.effect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CreatureCullTest {

    @Test
    void erasesLivingNonPlayers() {
        assertTrue(CreatureCullRule.appliesTo(true, false, false));
    }

    @Test
    void sparesPlayersPartsAndNonLiving() {
        assertFalse(CreatureCullRule.appliesTo(true, true, false));
        assertFalse(CreatureCullRule.appliesTo(true, false, true));
        assertFalse(CreatureCullRule.appliesTo(false, false, false));
        assertFalse(CreatureCullRule.appliesTo(false, true, false));
    }

    @Test
    void blocksJoinsOnlyWhileInvoked() {
        assertTrue(CreatureCullRule.shouldBlockJoin(true, true, false, false));
        assertFalse(CreatureCullRule.shouldBlockJoin(false, true, false, false));
        assertFalse(CreatureCullRule.shouldBlockJoin(true, true, true, false));
        assertFalse(CreatureCullRule.shouldBlockJoin(true, false, false, false));
    }
}
