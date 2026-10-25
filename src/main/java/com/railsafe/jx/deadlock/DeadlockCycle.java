package com.railsafe.jx.deadlock;

import java.util.List;
import java.util.Objects;

public record DeadlockCycle(List<String> trainIds) {
    public DeadlockCycle {
        trainIds = List.copyOf(Objects.requireNonNull(trainIds, "trainIds"));
        if (trainIds.size() < 2) throw new IllegalArgumentException("A deadlock cycle must contain at least two trains");
    }

    public String signature() {
        return trainIds.stream().sorted().reduce((a, b) -> a + "|" + b).orElse("");
    }

    @Override public String toString() { return String.join(" -> ", trainIds) + " -> " + trainIds.getFirst(); }
}
