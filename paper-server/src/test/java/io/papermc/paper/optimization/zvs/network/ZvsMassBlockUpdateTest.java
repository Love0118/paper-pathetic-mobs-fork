package io.papermc.paper.optimization.zvs.network;

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ZvsMassBlockUpdateTest {
    @Test
    void countsEveryChangedBlockAcrossSections() {
        final ShortSet first = new ShortOpenHashSet(new short[] {1, 2, 3});
        final ShortSet second = new ShortOpenHashSet(new short[] {4, 5});

        assertEquals(5, ZvsMassBlockUpdate.countChanges(new ShortSet[] {first, null, second}));
        assertEquals(2, ZvsMassBlockUpdate.countChangedSections(new ShortSet[] {first, null, second}));
    }

    @Test
    void replacementRequiresEnabledThresholdAndByteSavings() {
        assertFalse(ZvsMassBlockUpdate.shouldResend(false, 2, 3, 100, 50, 0));
        assertFalse(ZvsMassBlockUpdate.shouldResend(true, 3, 2, 100, 50, 0));
        assertFalse(ZvsMassBlockUpdate.shouldResend(true, 3, 3, 49, 50, 0));
        assertTrue(ZvsMassBlockUpdate.shouldResend(true, 3, 3, 50, 50, 0));
        assertFalse(ZvsMassBlockUpdate.shouldResend(true, 3, 3, 50, 50, 1));
    }

    @Test
    void incrementalEstimateIncludesEveryChangeAndSectionHeader() {
        assertEquals(38, ZvsMassBlockUpdate.estimateIncrementalBytes(2, 2));
    }
}
