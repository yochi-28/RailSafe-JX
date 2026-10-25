package com.railsafe.jx.model;

import java.util.Objects;

/** Immutable metadata for an exclusive railway resource. */
public final class TrackSegment {
    private final String id;
    private final String displayName;
    private final boolean exclusive;

    public TrackSegment(String id, String displayName, boolean exclusive) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.exclusive = exclusive;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public boolean isExclusive() { return exclusive; }

    @Override public String toString() { return displayName + " (" + id + ")"; }
}
