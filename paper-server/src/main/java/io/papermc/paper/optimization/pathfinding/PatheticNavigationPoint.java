package io.papermc.paper.optimization.pathfinding;

import de.bsommerfeld.pathetic.api.pathing.processing.Cost;
import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import net.minecraft.world.level.pathfinder.PathType;

record PatheticNavigationPoint(boolean traversable, Cost cost, PathType pathType, float malus) implements NavigationPoint {
    @Override
    public boolean isTraversable() {
        return this.traversable;
    }
}
