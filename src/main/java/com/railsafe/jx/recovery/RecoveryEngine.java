package com.railsafe.jx.recovery;

import com.railsafe.jx.deadlock.DeadlockCycle;
import com.railsafe.jx.model.Train;
import com.railsafe.jx.resource.ResourceManager;
import com.railsafe.jx.util.EventLogger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Applies a selected recovery policy and records both failed attempts and final outcomes. */
public final class RecoveryEngine implements AutoCloseable {
    private final Map<String, Train> trains;
    private final ResourceManager resources;
    private final EventLogger logger;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "railsafe-recovery-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<RecoveryAction> actions = new CopyOnWriteArrayList<>();
    private final Consumer<DeadlockCycle> resolvedCallback;
    private volatile RecoveryStrategy strategy = RecoveryStrategy.AUTO;
    private volatile long delayMillis = 500;

    public RecoveryEngine(Map<String, Train> trains, ResourceManager resources, EventLogger logger,
                          Consumer<DeadlockCycle> resolvedCallback) {
        this.trains = trains;
        this.resources = resources;
        this.logger = logger;
        this.resolvedCallback = resolvedCallback == null ? ignored -> { } : resolvedCallback;
    }

    public void setStrategy(RecoveryStrategy strategy) {
        this.strategy = strategy == null ? RecoveryStrategy.AUTO : strategy;
    }

    public RecoveryStrategy getStrategy() { return strategy; }
    public void setDelayMillis(long delayMillis) { this.delayMillis = Math.max(100, delayMillis); }
    public List<RecoveryAction> getActions() { return List.copyOf(actions); }

    public boolean resolve(DeadlockCycle cycle) {
        if (cycle == null) return false;
        List<Train> participants = cycle.trainIds().stream().map(trains::get).filter(t -> t != null).toList();
        if (participants.size() < 2) {
            logAction("SYSTEM", strategy, "Cycle references unavailable train records", "Recovery skipped", false);
            return false;
        }
        logger.log("RECOVERY_BEGIN", "SYSTEM", "Cycle " + cycle + "; requested strategy=" + strategy);
        boolean recovered = switch (strategy) {
            case REROUTE -> attemptReroute(participants, cycle) || attemptRelease(participants, cycle) || attemptDelay(participants, cycle);
            case RELEASE -> attemptRelease(participants, cycle) || attemptDelay(participants, cycle);
            case DELAY -> attemptDelay(participants, cycle) || attemptRelease(participants, cycle);
            case AUTO -> attemptReroute(participants, cycle) || attemptRelease(participants, cycle) || attemptDelay(participants, cycle);
        };
        if (recovered) {
            logger.log("RECOVERY_APPLIED", "SYSTEM", "Recovery action applied for cycle " + cycle + " using " + strategy + " policy; waiting for graph recheck");
            resolvedCallback.accept(cycle);
        } else {
            logger.log("RECOVERY_FAILED", "SYSTEM", "No safe recovery action succeeded for cycle " + cycle);
        }
        return recovered;
    }

    private boolean attemptReroute(List<Train> participants, DeadlockCycle cycle) {
        for (Train train : participants.stream().sorted(priorityOrder()).toList()) {
            List<String> alternative = train.findAlternateRouteFromCurrent();
            if (alternative.isEmpty()) continue;
            if (train.replaceRouteFromCurrent(alternative)) {
                resources.cancelPendingRequest(train.getId());
                String reason = "Alternate route " + alternative + " avoids the requested conflicting segment; priority=" + train.getPriority();
                logAction(train.getId(), RecoveryStrategy.REROUTE, reason, "Applied new route; request cancelled so the train can continue", true);
                return true;
            }
        }
        logAction(lowestPriority(participants).getId(), RecoveryStrategy.REROUTE,
                "No available alternate route from the train's current position for cycle " + cycle,
                "Reroute not applicable; trying safe fallback", false);
        return false;
    }

    private boolean attemptRelease(List<Train> participants, DeadlockCycle cycle) {
        for (Train train : participants.stream().sorted(priorityOrder()).toList()) {
            String result = resources.rollbackOrMoveToSiding(train, train.getSafeSidingId());
            boolean success = result != null && !result.startsWith("FAILED:");
            logAction(train.getId(), RecoveryStrategy.RELEASE,
                    "Safely recover a resource while resolving cycle " + cycle + "; wait=" + train.getWaitingDurationMillis() + " ms",
                    result == null ? "FAILED: no result" : result, success);
            if (success) {
                scheduler.schedule(() -> {
                    resources.resumeDelayedTrain(train.getId());
                    logger.log("ROLLBACK_FINISHED", train.getId(), "Safe rollback hold ended; train may rejoin its route");
                }, delayMillis, TimeUnit.MILLISECONDS);
                return true;
            }
        }
        return false;
    }

    private boolean attemptDelay(List<Train> participants, DeadlockCycle cycle) {
        for (Train train : participants.stream().sorted(priorityOrder()).toList()) {
            boolean moved = resources.moveToSidingForDelay(train, train.getSafeSidingId());
            if (!moved) {
                logAction(train.getId(), RecoveryStrategy.DELAY,
                        "Delay lower-priority train to an exclusive safe siding; cycle=" + cycle,
                        "FAILED: current segment or siding is not safely transferable", false);
                continue;
            }
            String reason = "Temporarily delay lower-priority train (priority=" + train.getPriority()
                    + ", accumulated wait=" + train.getTotalWaitingMillis() + " ms) on " + train.getSafeSidingId();
            logAction(train.getId(), RecoveryStrategy.DELAY, reason,
                    "Train is logically on the reserved siding; resume scheduled after " + delayMillis + " ms", true);
            scheduler.schedule(() -> {
                resources.resumeDelayedTrain(train.getId());
                logger.log("DELAY_FINISHED", train.getId(), "Temporary siding delay ended; route execution may resume");
            }, delayMillis, TimeUnit.MILLISECONDS);
            return true;
        }
        return false;
    }

    private Comparator<Train> priorityOrder() {
        // Lowest priority number is the preferred recovery victim; longer waiting breaks ties.
        return Comparator.comparingInt(Train::getPriority)
                .thenComparing(Comparator.comparingLong(Train::getTotalWaitingMillis).reversed());
    }

    private Train lowestPriority(List<Train> participants) { return participants.stream().min(priorityOrder()).orElseThrow(); }

    private void logAction(String trainId, RecoveryStrategy actionStrategy, String reason, String outcome, boolean success) {
        RecoveryAction action = new RecoveryAction(Instant.now(), trainId, actionStrategy, reason, outcome, success);
        actions.add(action);
        logger.log("RECOVERY_ACTION", trainId, actionStrategy + " | " + (success ? "SUCCESS" : "ATTEMPT FAILED")
                + " | " + reason + " | outcome=" + outcome);
    }

    @Override public void close() { scheduler.shutdownNow(); }
}
