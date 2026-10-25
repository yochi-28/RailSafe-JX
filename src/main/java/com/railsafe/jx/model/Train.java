package com.railsafe.jx.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Mutable train state. Mutations are synchronized so the UI and worker threads see a coherent view. */
public final class Train {
    private final String id;
    private final String name;
    private final int priority; // larger number means higher priority
    private final String destination;
    private final String safeSidingId;
    private final List<List<String>> alternateRoutes;
    private List<String> route;
    private int routeIndex = -1;
    private String currentSegment;
    private TrainStatus status = TrainStatus.READY;
    private long waitingSinceMillis;
    private long totalWaitingMillis;
    private long delayedSinceMillis;

    public Train(String id, String name, int priority, List<String> route,
                 List<List<String>> alternateRoutes, String safeSidingId) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        if (route == null || route.isEmpty()) throw new IllegalArgumentException("A train route cannot be empty");
        this.priority = priority;
        this.route = new ArrayList<>(route);
        this.destination = route.get(route.size() - 1);
        this.safeSidingId = Objects.requireNonNull(safeSidingId, "safeSidingId");
        this.alternateRoutes = new ArrayList<>();
        if (alternateRoutes != null) {
            for (List<String> alternative : alternateRoutes) {
                if (alternative != null && !alternative.isEmpty()) this.alternateRoutes.add(List.copyOf(alternative));
            }
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getPriority() { return priority; }
    public String getDestination() { return destination; }
    public String getSafeSidingId() { return safeSidingId; }
    public synchronized List<String> getRoute() { return List.copyOf(route); }
    public synchronized int getRouteIndex() { return routeIndex; }
    public synchronized String getCurrentSegment() { return currentSegment; }
    public synchronized TrainStatus getStatus() { return status; }
    public synchronized long getWaitingSinceMillis() { return waitingSinceMillis; }
    public synchronized long getTotalWaitingMillis() {
        return totalWaitingMillis + (waitingSinceMillis == 0 ? 0 : System.currentTimeMillis() - waitingSinceMillis);
    }
    public synchronized long getWaitingDurationMillis() {
        return waitingSinceMillis == 0 ? 0 : Math.max(0, System.currentTimeMillis() - waitingSinceMillis);
    }
    public synchronized boolean isCompleted() { return status == TrainStatus.COMPLETED; }

    public synchronized String getNextSegment() {
        int nextIndex = routeIndex + 1;
        return nextIndex >= 0 && nextIndex < route.size() ? route.get(nextIndex) : null;
    }

    public synchronized void moveToSegment(String segmentId) {
        Objects.requireNonNull(segmentId, "segmentId");
        if (routeIndex + 1 < route.size() && route.get(routeIndex + 1).equals(segmentId)) {
            routeIndex++;
        } else if (routeIndex < 0 && !route.isEmpty() && route.getFirst().equals(segmentId)) {
            routeIndex = 0;
        }
        currentSegment = segmentId;
        status = TrainStatus.RUNNING;
        clearWaitingTimer();
    }

    public synchronized void rollbackToRouteIndex(int newIndex, String segmentId) {
        if (newIndex < 0 || newIndex >= route.size() || !route.get(newIndex).equals(segmentId)) {
            throw new IllegalArgumentException("Rollback target must be a valid point on the active route");
        }
        routeIndex = newIndex;
        currentSegment = segmentId;
        status = TrainStatus.DELAYED;
        clearWaitingTimer();
        delayedSinceMillis = System.currentTimeMillis();
    }

    /** Move to a siding without advancing the planned route index. */
    public synchronized void moveToSiding(String sidingId, boolean delayed) {
        currentSegment = sidingId;
        clearWaitingTimer();
        if (delayed) {
            status = TrainStatus.DELAYED;
            delayedSinceMillis = System.currentTimeMillis();
        } else {
            status = TrainStatus.RUNNING;
        }
    }

    public synchronized boolean replaceRouteFromCurrent(List<String> newRoute) {
        if (newRoute == null || newRoute.isEmpty() || currentSegment == null) return false;
        if (!newRoute.getFirst().equals(currentSegment)) return false;
        if (newRoute.size() < 2 && !newRoute.getLast().equals(destination)) return false;
        route = new ArrayList<>(newRoute);
        routeIndex = 0;
        status = TrainStatus.RUNNING;
        clearWaitingTimer();
        return true;
    }

    public synchronized List<String> findAlternateRouteFromCurrent() {
        if (currentSegment == null) return List.of();
        for (List<String> candidate : alternateRoutes) {
            if (candidate.size() > 1 && candidate.getFirst().equals(currentSegment)
                    && candidate.getLast().equals(destination) && !candidate.equals(route)) {
                return candidate;
            }
        }
        return List.of();
    }

    public synchronized void markWaiting() {
        status = TrainStatus.WAITING;
        if (waitingSinceMillis == 0) waitingSinceMillis = System.currentTimeMillis();
    }

    public synchronized void setStatus(TrainStatus newStatus) {
        status = Objects.requireNonNull(newStatus, "newStatus");
        if (newStatus != TrainStatus.WAITING) clearWaitingTimer();
        if (newStatus == TrainStatus.DELAYED) delayedSinceMillis = System.currentTimeMillis();
    }

    public synchronized long getDelayedSinceMillis() { return delayedSinceMillis; }

    public synchronized void clearPositionAfterFailure() {
        currentSegment = null;
        status = TrainStatus.FAILED;
        clearWaitingTimer();
    }

    private void clearWaitingTimer() {
        if (waitingSinceMillis != 0) {
            totalWaitingMillis += Math.max(0, System.currentTimeMillis() - waitingSinceMillis);
            waitingSinceMillis = 0;
        }
    }

    public synchronized void resetState() {
        routeIndex = -1;
        currentSegment = null;
        status = TrainStatus.READY;
        waitingSinceMillis = 0;
        totalWaitingMillis = 0;
        delayedSinceMillis = 0;
    }
}
