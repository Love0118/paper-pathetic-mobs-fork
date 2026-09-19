package io.papermc.paper.optimization.mud;

import java.util.HashSet;
import net.minecraft.world.entity.Mob;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Normal
class MudPresentationTest {
    @Test void allOwnershipAndGameplayGuardsAreRequired() {
        final Mob mob = mock(Mob.class);
        final HashSet<String> tags = new HashSet<>();
        when(mob.entityTags()).thenReturn(tags);
        when(mob.isNoAi()).thenReturn(true);
        when(mob.isNoGravity()).thenReturn(true);
        when(mob.isInvulnerable()).thenReturn(true);
        when(mob.isAlive()).thenReturn(true);
        mob.noPhysics = true;
        assertFalse(MudPresentation.eligible(mob));
        tags.add(MudPresentation.MARKER);
        assertTrue(MudPresentation.eligible(mob));
        when(mob.isNoAi()).thenReturn(false); assertFalse(MudPresentation.eligible(mob));
        when(mob.isNoAi()).thenReturn(true);
        mob.noPhysics = false; assertFalse(MudPresentation.eligible(mob)); mob.noPhysics = true;
        when(mob.isNoGravity()).thenReturn(false); assertFalse(MudPresentation.eligible(mob));
        when(mob.isNoGravity()).thenReturn(true);
        when(mob.isInvulnerable()).thenReturn(false); assertFalse(MudPresentation.eligible(mob));
        when(mob.isInvulnerable()).thenReturn(true);
        when(mob.isAlive()).thenReturn(false); assertFalse(MudPresentation.eligible(mob));
        when(mob.isAlive()).thenReturn(true);
        when(mob.isPassenger()).thenReturn(true); assertFalse(MudPresentation.eligible(mob));
        when(mob.isPassenger()).thenReturn(false);
        when(mob.isVehicle()).thenReturn(true); assertFalse(MudPresentation.eligible(mob));
    }
}
