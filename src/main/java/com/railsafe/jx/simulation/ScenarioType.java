package com.railsafe.jx.simulation;

public enum ScenarioType {
    NORMAL("Normal Traffic"), DEADLOCK_DEMO("Deterministic Deadlock Demo"), CUSTOM("Custom Railway Scenario");
    private final String displayName;
    ScenarioType(String displayName) { this.displayName = displayName; }
    public String getDisplayName() { return displayName; }
    @Override public String toString() { return displayName; }
}
