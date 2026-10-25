package com.railsafe.jx.util;

public interface EventSink extends AutoCloseable {
    default void startRun(String scenarioName) { }
    void accept(SimulationEvent event);
    default void finishRun(String outcome) { }
    @Override default void close() { }
}
