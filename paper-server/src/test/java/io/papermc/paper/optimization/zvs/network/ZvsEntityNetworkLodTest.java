package io.papermc.paper.optimization.zvs.network;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ZvsEntityNetworkLodTest {
    @Test
    void assignsStableDistanceTiers() {
        assertEquals(ZvsEntityNetworkLod.Tier.NEAR, ZvsEntityNetworkLod.tier(32.0 * 32.0, 32, 64));
        assertEquals(ZvsEntityNetworkLod.Tier.MEDIUM, ZvsEntityNetworkLod.tier(33.0 * 33.0, 32, 64));
        assertEquals(ZvsEntityNetworkLod.Tier.FAR, ZvsEntityNetworkLod.tier(65.0 * 65.0, 32, 64));
    }

    @Test
    void cadenceIsDeterministicAndDistributedByEntityAndViewer() {
        int allowed = 0;
        for (long tick = 0; tick < 8; tick++) {
            if (ZvsEntityNetworkLod.cadenceAllows(tick, 17, 4, 4)) {
                allowed++;
            }
        }
        assertEquals(2, allowed);
        assertTrue(ZvsEntityNetworkLod.cadenceAllows(0, 17, 4, 1));
        assertFalse(
            ZvsEntityNetworkLod.cadenceAllows(0, 17, 4, 4)
                && ZvsEntityNetworkLod.cadenceAllows(1, 17, 4, 4)
        );
    }
}
