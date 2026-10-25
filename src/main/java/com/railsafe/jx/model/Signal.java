package com.railsafe.jx.model;

import java.util.Objects;

/** Simple signal model; a segment with an owner displays RED. */
public final class Signal {
    private final String id;
    private final String protectedSegmentId;
    private volatile SignalAspect aspect = SignalAspect.GREEN;

    public Signal(String id, String protectedSegmentId) {
        this.id = Objects.requireNonNull(id, "id");
        this.protectedSegmentId = Objects.requireNonNull(protectedSegmentId, "protectedSegmentId");
    }

    public String getId() { return id; }
    public String getProtectedSegmentId() { return protectedSegmentId; }
    public SignalAspect getAspect() { return aspect; }
    public void updateForOccupancy(boolean occupied) {
        aspect = occupied ? SignalAspect.RED : SignalAspect.GREEN;
    }
}
