package com.railsafe.jx.simulation;

public record SimulationConfig(int trainStepDelayMillis, int deadlockCheckMillis) {
    public SimulationConfig {
        if (trainStepDelayMillis < 10) trainStepDelayMillis = 10;
        if (deadlockCheckMillis < 25) deadlockCheckMillis = 25;
    }
    public static SimulationConfig defaults() { return new SimulationConfig(450, 100); }
}
