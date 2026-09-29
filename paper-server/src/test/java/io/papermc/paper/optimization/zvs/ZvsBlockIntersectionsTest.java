package io.papermc.paper.optimization.zvs;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Normal
class ZvsBlockIntersectionsTest {
    @Test void preservesVanillaVisitedBlocksAndStepOrderAcrossMovementAndRepeatedCalls() {
        for (int pass = 0; pass < 2; pass++) {
            for (Vec3 to : List.of(Vec3.ZERO, new Vec3(.25, 0, .75), new Vec3(.01, 0, .02), new Vec3(1, 2, 3), new Vec3(-6, -2, 4), new Vec3(20, 0, 0))) {
                for (double width : new double[]{.6, 2, 4}) {
                    Vec3 from = new Vec3(.25, 0, .75);
                    AABB bounds = new AABB(to.x - width / 2, to.y, to.z - width / 2, to.x + width / 2, to.y + 1.95, to.z + width / 2);
                    var expected = new ArrayList<String>();
                    var actual = new ArrayList<String>();
                    assertTrue(BlockGetter.forEachBlockIntersectedBetween(from, to, bounds, (p, step) -> {
                        expected.add(p.asLong() + ":" + step); return true;
                    }));
                    assertTrue(ZvsBlockIntersections.visit(from, to, bounds, (p, step) -> {
                        actual.add(p.asLong() + ":" + step); return true;
                    }));
                    assertEquals(expected, actual);
                }
            }
        }
    }

    @Test void nestedVisitsCancellationAndExceptionsDoNotContaminateOtherTraversals() {
        Vec3 from = Vec3.ZERO, to = new Vec3(5, 0, 5);
        AABB bounds = new AABB(4.7, 0, 4.7, 5.3, 1.95, 5.3);
        List<Long> expected = new ArrayList<>();
        BlockGetter.forEachBlockIntersectedBetween(from, to, bounds, (p, step) -> { expected.add(p.asLong()); return true; });
        assertFalse(ZvsBlockIntersections.visit(from, to, bounds, (p, step) -> false));
        assertThrows(IllegalStateException.class, () -> ZvsBlockIntersections.visit(from, to, bounds, (p, step) -> { throw new IllegalStateException(); }));
        List<Long> actual = new ArrayList<>();
        ZvsBlockIntersections.visit(from, to, bounds, (p, step) -> {
            List<Long> nested = new ArrayList<>();
            ZvsBlockIntersections.visit(from, to, bounds, (n, s) -> { nested.add(n.asLong()); return true; });
            assertEquals(expected, nested);
            actual.add(p.asLong());
            return true;
        });
        assertEquals(expected, actual);
    }
}
