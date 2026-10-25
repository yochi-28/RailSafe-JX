package com.railsafe.jx.simulation;

import com.railsafe.jx.model.Train;
import com.railsafe.jx.model.TrainStatus;
import com.railsafe.jx.resource.AcquireResult;


/** One train's independent execution loop. */
final class TrainTask implements Runnable {
    private final SimulationManager manager;
    private final Train train;
    TrainTask(SimulationManager manager, Train train) {
        this.manager = manager;
        this.train = train;
    }

    @Override public void run() {
        try {
            while (manager.isRunning() && !train.isCompleted()) {
                manager.awaitIfPaused();
                if (!manager.isRunning()) break;
                manager.getResourceManager().awaitResumeIfDelayed(train.getId());
                if (!manager.isRunning()) break;
                String nextSegment = train.getNextSegment();
                if (nextSegment == null) {
                    String current = train.getCurrentSegment();
                    train.setStatus(TrainStatus.COMPLETED);
                    if (current != null) manager.getResourceManager().release(train.getId(), current);
                    manager.getEventLogger().log("TRAIN_COMPLETED", train.getId(), "Reached destination " + train.getDestination());
                    break;
                }

                AcquireResult result = manager.getResourceManager().acquire(train.getId(), nextSegment, train);
                if (result == AcquireResult.INTERRUPTED) break;
                if (result == AcquireResult.CANCELLED) {
                    manager.getEventLogger().log("REQUEST_CANCELLED", train.getId(), "Pending request cancelled by recovery; re-evaluating route/position");
                    continue;
                }

                // A pause may be requested immediately after a reservation succeeds. Stop here
                // before changing the logical position; the new segment remains safely reserved.
                manager.awaitIfPaused();
                if (!manager.isRunning()) break;
                String previous = train.getCurrentSegment();
                train.moveToSegment(nextSegment);
                if (previous != null && !previous.equals(nextSegment)) manager.getResourceManager().release(train.getId(), previous);
                manager.getEventLogger().log("TRAIN_MOVED", train.getId(), "Entered " + nextSegment + "; next=" + train.getNextSegment());

                if (manager.shouldAwaitFirstTrackBarrier(train)) {
                    manager.awaitFirstTrackBarrier();
                }
                Thread.sleep(manager.getStepDelayMillis());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (manager.isRunning()) {
                manager.getResourceManager().releaseAllForFailedTrain(train);
                manager.getEventLogger().log("TRAIN_INTERRUPTED", train.getId(), "Unexpected worker interruption; reservations released and train marked failed");
            }
        } catch (RuntimeException ex) {
            manager.getResourceManager().releaseAllForFailedTrain(train);
            manager.getEventLogger().log("TRAIN_FAILED", train.getId(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            manager.onTrainTaskFinished(train);
        }
    }
}
