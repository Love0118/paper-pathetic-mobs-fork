package io.papermc.paper.optimization.pathfinding;

import org.jspecify.annotations.NullMarked;

@NullMarked
final class PatheticEvaluationBudget {
    private final int initial;
    private int remaining;
    private boolean exhausted;

    PatheticEvaluationBudget(final int remaining) {
        this.initial = remaining;
        this.remaining = remaining;
    }

    boolean tryConsume() {
        if (this.remaining <= 0) {
            this.exhausted = true;
            return false;
        }
        this.remaining--;
        return true;
    }

    int remaining() {
        return this.remaining;
    }

    int consumed() {
        return this.initial - this.remaining;
    }

    boolean exhausted() {
        return this.exhausted;
    }
}
