package com.railsafe.jx.resource;

import com.railsafe.jx.model.Train;
import com.railsafe.jx.model.TrainStatus;
import com.railsafe.jx.util.EventLogger;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns all exclusive track reservations. The lock is held only while inspecting/updating maps;
 * Condition.await releases it, allowing the detector to snapshot a live wait-for graph.
 */
public final class ResourceManager {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final Map<String, String> owners = new HashMap<>();
    private final Map<String, String> pendingRequests = new HashMap<>();
    private final Set<String> cancelledRequests = new HashSet<>();
    private final Set<String> delayedTrains = new HashSet<>();
    private final EventLogger logger;
    private boolean shutdown;
    private boolean paused;

    public ResourceManager(EventLogger logger) { this.logger = logger; }

    public AcquireResult acquire(String trainId, String segmentId, Train train) {
        boolean loggedWait = false;
        try {
            lock.lockInterruptibly();
            try {
                while (!shutdown) {
                    if (cancelledRequests.remove(trainId)) {
                        pendingRequests.remove(trainId);
                        changed.signalAll();
                        return AcquireResult.CANCELLED;
                    }
                    if (paused) {
                        train.setStatus(TrainStatus.PAUSED);
                        changed.await(150, TimeUnit.MILLISECONDS);
                        continue;
                    }
                    String owner = owners.get(segmentId);
                    if (owner == null || owner.equals(trainId)) {
                        owners.put(segmentId, trainId);
                        pendingRequests.remove(trainId);
                        train.setStatus(TrainStatus.RUNNING);
                        changed.signalAll();
                        logger.log("RESOURCE_ACQUIRED", trainId, "Reserved segment " + segmentId);
                        return AcquireResult.ACQUIRED;
                    }
                    pendingRequests.put(trainId, segmentId);
                    train.markWaiting();
                    if (!loggedWait) {
                        logger.log("WAITING", trainId, "Waiting for segment " + segmentId + " owned by " + owner);
                        loggedWait = true;
                    }
                    changed.await(150, TimeUnit.MILLISECONDS);
                }
                pendingRequests.remove(trainId);
                return AcquireResult.INTERRUPTED;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException ex) {
            lock.lock();
            try {
                pendingRequests.remove(trainId);
                cancelledRequests.remove(trainId);
                changed.signalAll();
            } finally { lock.unlock(); }
            Thread.currentThread().interrupt();
            return AcquireResult.INTERRUPTED;
        }
    }

    /** Non-blocking reservation; useful for startup setup and deterministic unit tests. */
    public boolean tryAcquire(String trainId, String segmentId) {
        lock.lock();
        try {
            String owner = owners.get(segmentId);
            if (owner != null && !owner.equals(trainId)) return false;
            owners.put(segmentId, trainId);
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    public boolean release(String trainId, String segmentId) {
        if (segmentId == null) return false;
        lock.lock();
        try {
            if (!trainId.equals(owners.get(segmentId))) return false;
            owners.remove(segmentId);
            logger.log("RESOURCE_RELEASED", trainId, "Released segment " + segmentId);
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /** Cancels only the pending request, not any currently occupied segment. */
    public void cancelPendingRequest(String trainId) {
        lock.lock();
        try {
            boolean wasPending = pendingRequests.remove(trainId) != null;
            if (wasPending) cancelledRequests.add(trainId);
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    /**
     * Safe delay recovery: reserve an exclusive siding, update the train's logical position,
     * and only then release its previously occupied segment. This is one locked transaction.
     */
    public boolean moveToSidingForDelay(Train train, String sidingId) {
        lock.lock();
        try {
            String current = train.getCurrentSegment();
            if (current == null || !train.getId().equals(owners.get(current))) return false;
            String sidingOwner = owners.get(sidingId);
            if (sidingOwner != null && !sidingOwner.equals(train.getId())) return false;
            owners.put(sidingId, train.getId());
            train.moveToSiding(sidingId, true);
            owners.remove(current);
            boolean wasPending = pendingRequests.remove(train.getId()) != null;
            if (wasPending) cancelledRequests.add(train.getId());
            delayedTrains.add(train.getId());
            logger.log("SAFE_TRANSFER", train.getId(), "Moved from " + current + " to siding " + sidingId + " before releasing the occupied segment");
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /**
     * Controlled rollback: reserve the previous route segment before moving the train and
     * releasing its current segment. If rollback is unsafe, fall back to its dedicated siding.
     */
    public String rollbackOrMoveToSiding(Train train, String sidingId) {
        lock.lock();
        try {
            String current = train.getCurrentSegment();
            if (current == null || !train.getId().equals(owners.get(current))) return "FAILED: train does not own its current segment";
            int index = train.getRouteIndex();
            var route = train.getRoute();
            if (index > 0) {
                String previous = route.get(index - 1);
                String previousOwner = owners.get(previous);
                if (previousOwner == null || previousOwner.equals(train.getId())) {
                    owners.put(previous, train.getId());
                    train.rollbackToRouteIndex(index - 1, previous);
                    owners.remove(current);
                    boolean wasPending = pendingRequests.remove(train.getId()) != null;
                    if (wasPending) cancelledRequests.add(train.getId());
                    delayedTrains.add(train.getId());
                    logger.log("SAFE_ROLLBACK", train.getId(), "Rolled back from " + current + " to previously visited segment " + previous + " and held safely before rejoining route");
                    changed.signalAll();
                    return "Rolled back from " + current + " to " + previous + "; holding briefly at the safe position before rejoining route";
                }
            }
            String sidingOwner = owners.get(sidingId);
            if (sidingOwner == null || sidingOwner.equals(train.getId())) {
                owners.put(sidingId, train.getId());
                train.moveToSiding(sidingId, true);
                owners.remove(current);
                boolean wasPending = pendingRequests.remove(train.getId()) != null;
                if (wasPending) cancelledRequests.add(train.getId());
                delayedTrains.add(train.getId());
                logger.log("SAFE_FALLBACK", train.getId(), "Rollback unavailable; moved to safe siding " + sidingId + " before releasing " + current);
                changed.signalAll();
                return "Rollback unavailable; moved safely to siding " + sidingId + " and held briefly before rejoining route";
            }
            return "FAILED: previous segment and fallback siding are both occupied";
        } finally { lock.unlock(); }
    }

    public void setPaused(boolean paused) {
        lock.lock();
        try {
            this.paused = paused;
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    public void resumeDelayedTrain(String trainId) {
        lock.lock();
        try {
            delayedTrains.remove(trainId);
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    public void awaitResumeIfDelayed(String trainId) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (!shutdown && delayedTrains.contains(trainId)) changed.await();
        } finally { lock.unlock(); }
    }

    public Map<String, Set<String>> getWaitForGraph() {
        lock.lock();
        try {
            Map<String, Set<String>> graph = new LinkedHashMap<>();
            for (Map.Entry<String, String> request : pendingRequests.entrySet()) {
                String owner = owners.get(request.getValue());
                if (owner != null && !owner.equals(request.getKey())) {
                    graph.computeIfAbsent(request.getKey(), ignored -> new LinkedHashSet<>()).add(owner);
                    graph.computeIfAbsent(owner, ignored -> new LinkedHashSet<>());
                }
            }
            Map<String, Set<String>> result = new LinkedHashMap<>();
            graph.forEach((key, value) -> result.put(key, Collections.unmodifiableSet(new LinkedHashSet<>(value))));
            return Collections.unmodifiableMap(result);
        } finally { lock.unlock(); }
    }

    public Map<String, String> getOwnershipSnapshot() {
        lock.lock();
        try { return Collections.unmodifiableMap(new LinkedHashMap<>(owners)); }
        finally { lock.unlock(); }
    }

    public String ownerOf(String segmentId) {
        lock.lock();
        try { return owners.get(segmentId); }
        finally { lock.unlock(); }
    }

    public boolean isOwnershipMapConsistent() {
        lock.lock();
        try {
            for (Map.Entry<String, String> entry : owners.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) return false;
            }
            return true;
        } finally { lock.unlock(); }
    }


    /** Releases every reservation for a train that has failed, after clearing its logical position. */
    public void releaseAllForFailedTrain(Train train) {
        lock.lock();
        try {
            train.clearPositionAfterFailure();
            var iterator = owners.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, String> entry = iterator.next();
                if (train.getId().equals(entry.getValue())) {
                    logger.log("RESOURCE_RELEASED", train.getId(), "Released segment " + entry.getKey() + " during failed-train cleanup");
                    iterator.remove();
                }
            }
            pendingRequests.remove(train.getId());
            cancelledRequests.remove(train.getId());
            delayedTrains.remove(train.getId());
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    /** Stable-state invariant check: every active train position must be owned by that train. */
    public boolean isOwnershipConsistentWithTrains(Map<String, Train> trains) {
        lock.lock();
        try {
            for (Map.Entry<String, String> entry : owners.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || !trains.containsKey(entry.getValue())) return false;
            }
            for (Train train : trains.values()) {
                if (train.getStatus() == TrainStatus.COMPLETED || train.getStatus() == TrainStatus.FAILED) continue;
                String current = train.getCurrentSegment();
                if (current != null && !train.getId().equals(owners.get(current))) return false;
            }
            return true;
        } finally { lock.unlock(); }
    }

    public boolean hasWaitingRequest(String trainId) {
        lock.lock();
        try { return pendingRequests.containsKey(trainId); }
        finally { lock.unlock(); }
    }

    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            pendingRequests.clear();
            delayedTrains.clear();
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    /** Reset is permitted only when old worker tasks have been shut down. */
    public void clearForReset() {
        lock.lock();
        try {
            owners.clear();
            pendingRequests.clear();
            cancelledRequests.clear();
            delayedTrains.clear();
            shutdown = false;
            paused = false;
            changed.signalAll();
        } finally { lock.unlock(); }
    }
}
