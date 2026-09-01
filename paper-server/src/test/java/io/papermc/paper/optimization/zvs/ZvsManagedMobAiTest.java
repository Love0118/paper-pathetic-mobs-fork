package io.papermc.paper.optimization.zvs;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ZvsManagedMobAiTest {
    @Test
    void selectorPhaseSpreadsEntitiesAndRunsExactlyEveryInterval() {
        assertTrue(ZvsManagedMobAi.selectorPhase(8, 4, 4));
        assertFalse(ZvsManagedMobAi.selectorPhase(9, 4, 4));
        assertTrue(ZvsManagedMobAi.selectorPhase(9, 3, 4));
        assertTrue(ZvsManagedMobAi.selectorPhase(9, 3, 0));
    }

    @Test
    void fullRateDistanceUsesTheHorizontalPlaneAndClampsNegativeConfiguration() {
        assertFalse(ZvsManagedMobAi.outsideFullRateDistance(0.0D, 0.0D, 12.0D, 0.0D, 12.0D));
        assertTrue(ZvsManagedMobAi.outsideFullRateDistance(0.0D, 0.0D, 12.01D, 0.0D, 12.0D));
        assertTrue(ZvsManagedMobAi.outsideFullRateDistance(0.0D, 0.0D, 1.0D, 0.0D, -1.0D));
    }
}
