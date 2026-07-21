package io.papermc.paper.optimization.pathfinding;

final class PatheticEvaluationBudget {
    private int remaining;
    private boolean exhausted;

    PatheticEvaluationBudget(final int remaining) {
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

    boolean exhausted() {
        return this.exhausted;
    }
}
