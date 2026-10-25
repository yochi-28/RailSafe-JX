package com.railsafe.jx.simulation;

import com.railsafe.jx.deadlock.DeadlockCycle;
import com.railsafe.jx.deadlock.DeadlockDetector;
import com.railsafe.jx.model.Signal;
import com.railsafe.jx.model.TrackSegment;
import com.railsafe.jx.model.Train;
import com.railsafe.jx.model.TrainStatus;
import com.railsafe.jx.recovery.RecoveryEngine;
import com.railsafe.jx.recovery.RecoveryStrategy;
import com.railsafe.jx.resource.ResourceManager;
import com.railsafe.jx.util.EventLogger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** Orchestrates train workers, resource management, detection and recovery. */
public final class SimulationManager implements AutoCloseable {
    private final EventLogger eventLogger;
    private final DeadlockDetector detector = new DeadlockDetector();
    private final ReentrantLock pauseLock = new ReentrantLock();
    private final Condition resumed = pauseLock.newCondition();
    private volatile Map<String, Train> trains = Map.of();
    private volatile Map<String, TrackSegment> tracks = Map.of();
    private volatile Map<String, Signal> signals = Map.of();
    private volatile List<String[]> connections = List.of();
    private volatile ResourceManager resourceManager;
    private volatile RecoveryEngine recoveryEngine;
    private volatile ExecutorService workers;
    private volatile ScheduledExecutorService monitor;
    private volatile ScenarioType scenarioType = ScenarioType.NORMAL;
    private volatile String scenarioDisplayName = ScenarioType.NORMAL.getDisplayName();
    private volatile Set<String> barrierTrainIds = Set.of();
    private volatile boolean running;
    private volatile boolean paused;
    private volatile int stepDelayMillis = SimulationConfig.defaults().trainStepDelayMillis();
    private volatile List<DeadlockCycle> activeDeadlocks = List.of();
    // Retain the most recent cycle after recovery so the dashboard can show a post-mortem graph.
    private volatile DeadlockCycle lastDetectedDeadlock;
    private volatile boolean lastDetectedDeadlockResolved;
    private final Set<String> processingCycles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, DeadlockCycle> pendingResolutionChecks = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger resolvedDeadlocks = new AtomicInteger();
    private final AtomicInteger completedTrains = new AtomicInteger();
    private volatile CountDownLatch firstTrackBarrier;
    private volatile boolean activeRunSession;

    public SimulationManager(EventLogger eventLogger) {
        this.eventLogger = eventLogger == null ? new EventLogger() : eventLogger;
        reset(ScenarioType.NORMAL);
    }

    public synchronized void reset(ScenarioType type) {
        ScenarioType requested = type == null ? ScenarioType.NORMAL : type;
        if (requested == ScenarioType.CUSTOM) requested = ScenarioType.NORMAL;
        ScenarioFactory.ScenarioData data = ScenarioFactory.create(requested);
        Set<String> barrierIds = requested == ScenarioType.DEADLOCK_DEMO
                ? Set.of("T1", "T2") : Set.of();
        configureScenario(requested, data, requested.getDisplayName(), barrierIds);
    }

    /** Loads a user-built scenario into the same concurrent simulation, detector and recovery engine. */
    public synchronized void resetCustom(ScenarioFactory.ScenarioData data, String displayName,
                                         boolean synchronizeFirstTwoTrains) {
        if (data == null || data.trains() == null || data.tracks() == null || data.trains().isEmpty() || data.tracks().isEmpty()) {
            throw new IllegalArgumentException("A custom scenario needs at least one train and one track/platform resource");
        }
        List<String> trainIds = new java.util.ArrayList<>(data.trains().keySet());
        if (synchronizeFirstTwoTrains && trainIds.size() < 2) {
            throw new IllegalArgumentException("A deterministic deadlock demo requires at least two trains");
        }
        Set<String> barrierIds = synchronizeFirstTwoTrains
                ? Set.of(trainIds.get(0), trainIds.get(1)) : Set.of();
        configureScenario(ScenarioType.CUSTOM, data,
                displayName == null || displayName.isBlank() ? "Custom Railway Scenario" : displayName,
                barrierIds);
    }

    private void configureScenario(ScenarioType type, ScenarioFactory.ScenarioData data,
                                   String displayName, Set<String> trainIdsAtBarrier) {
        stopExecutors("RESET");
        scenarioType = type;
        scenarioDisplayName = displayName;
        barrierTrainIds = Set.copyOf(trainIdsAtBarrier);
        trains = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data.trains()));
        tracks = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data.tracks()));
        signals = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data.signals()));
        connections = List.copyOf(data.connections());
        resourceManager = new ResourceManager(eventLogger);
        activeDeadlocks = List.of();
        lastDetectedDeadlock = null;
        lastDetectedDeadlockResolved = false;
        processingCycles.clear();
        pendingResolutionChecks.clear();
        resolvedDeadlocks.set(0);
        completedTrains.set(0);
        paused = false;
        running = false;
        firstTrackBarrier = barrierTrainIds.size() >= 2 ? new CountDownLatch(barrierTrainIds.size()) : null;
        recoveryEngine = new RecoveryEngine(trains, resourceManager, eventLogger,
                cycle -> pendingResolutionChecks.put(cycle.signature(), cycle));
        recoveryEngine.setDelayMillis(Math.max(200, stepDelayMillis * 2L));
        eventLogger.log("SIMULATION_RESET", "SYSTEM", "Loaded scenario: " + scenarioDisplayName);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        paused = false;
        activeRunSession = true;
        eventLogger.startRun(scenarioDisplayName);
        eventLogger.log("SIMULATION_STARTED", "SYSTEM", "Starting " + scenarioDisplayName + " with " + trains.size() + " trains");
        workers = Executors.newFixedThreadPool(Math.max(3, trains.size()), r -> {
            Thread thread = new Thread(r, "railsafe-train-worker");
            thread.setDaemon(true);
            return thread;
        });
        for (Train train : trains.values()) workers.submit(new TrainTask(this, train));
        monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "railsafe-deadlock-monitor");
            thread.setDaemon(true);
            return thread;
        });
        monitor.scheduleWithFixedDelay(this::checkDeadlocksSafely, 0,
                SimulationConfig.defaults().deadlockCheckMillis(), TimeUnit.MILLISECONDS);
    }

    public void pause() {
        pauseLock.lock();
        try {
            if (running) {
                paused = true;
                resourceManager.setPaused(true);
                eventLogger.log("SIMULATION_PAUSED", "SYSTEM", "Track acquisition and movement pause at safe worker checkpoints");
            }
        } finally { pauseLock.unlock(); }
    }

    public void resume() {
        pauseLock.lock();
        try {
            paused = false;
            resourceManager.setPaused(false);
            resumed.signalAll();
            eventLogger.log("SIMULATION_RESUMED", "SYSTEM", "Simulation resumed");
        } finally { pauseLock.unlock(); }
    }

    void awaitIfPaused() throws InterruptedException {
        pauseLock.lockInterruptibly();
        try { while (paused && running) resumed.await(); }
        finally { pauseLock.unlock(); }
    }

    private void checkDeadlocksSafely() {
        if (!running || paused) return;
        try {
            List<DeadlockCycle> cycles = detector.findCycles(resourceManager.getWaitForGraph());
            activeDeadlocks = cycles;
            Set<String> currentSignatures = cycles.stream().map(DeadlockCycle::signature).collect(Collectors.toSet());
            processingCycles.removeIf(signature -> !currentSignatures.contains(signature));
            for (Map.Entry<String, DeadlockCycle> pending : pendingResolutionChecks.entrySet()) {
                if (!currentSignatures.contains(pending.getKey())
                        && pendingResolutionChecks.remove(pending.getKey(), pending.getValue())) {
                    resolvedDeadlocks.incrementAndGet();
                    if (lastDetectedDeadlock != null
                            && lastDetectedDeadlock.signature().equals(pending.getKey())) {
                        lastDetectedDeadlockResolved = true;
                    }
                    eventLogger.log("DEADLOCK_RESOLVED", "SYSTEM",
                            "Wait-for graph recheck confirmed that cycle cleared: " + pending.getValue());
                }
            }
            for (DeadlockCycle cycle : cycles) {
                lastDetectedDeadlock = cycle;
                lastDetectedDeadlockResolved = false;
                if (processingCycles.add(cycle.signature())) {
                    eventLogger.log("DEADLOCK_DETECTED", "SYSTEM", "Cycle detected in wait-for graph: " + cycle);
                    boolean resolved = recoveryEngine.resolve(cycle);
                    if (!resolved) eventLogger.log("DEADLOCK_UNRESOLVED", "SYSTEM", "No safe action resolved cycle " + cycle + "; duplicate recovery suppressed until graph changes");
                }
            }
            for (Signal signal : signals.values()) signal.updateForOccupancy(resourceManager.ownerOf(signal.getProtectedSegmentId()) != null);
        } catch (RuntimeException ex) {
            eventLogger.log("DETECTOR_ERROR", "SYSTEM", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    void onTrainTaskFinished(Train train) {
        if (train.getStatus() == TrainStatus.COMPLETED) completedTrains.updateAndGet(current -> Math.max(current, (int) trains.values().stream().filter(Train::isCompleted).count()));
        if (running && trains.values().stream().allMatch(t -> t.isCompleted() || t.getStatus() == TrainStatus.FAILED)) {
            running = false;
            pauseLock.lock();
            try { paused = false; resumed.signalAll(); } finally { pauseLock.unlock(); }
            eventLogger.log("SIMULATION_FINISHED", "SYSTEM", "All train tasks ended. Completed=" + completedTrains.get());
            eventLogger.finishRun("COMPLETED");
            activeRunSession = false;
            ScheduledExecutorService currentMonitor = monitor;
            if (currentMonitor != null) currentMonitor.shutdown();
        }
    }

    private synchronized void stopExecutors(String outcome) {
        running = false;
        pauseLock.lock();
        try { paused = false; resumed.signalAll(); } finally { pauseLock.unlock(); }
        if (monitor != null) monitor.shutdownNow();
        if (workers != null) workers.shutdownNow();
        if (resourceManager != null) resourceManager.shutdown();
        if (recoveryEngine != null) recoveryEngine.close();
        try {
            if (workers != null) workers.awaitTermination(700, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        if (outcome != null && eventLogger != null && activeRunSession) {
            eventLogger.finishRun(outcome);
            activeRunSession = false;
        }
        workers = null;
        monitor = null;
    }

    public void setRecoveryStrategy(RecoveryStrategy strategy) { recoveryEngine.setStrategy(strategy); }
    public RecoveryStrategy getRecoveryStrategy() { return recoveryEngine.getStrategy(); }
    public void setStepDelayMillis(int millis) {
        stepDelayMillis = Math.max(50, Math.min(3000, millis));
        recoveryEngine.setDelayMillis(Math.max(200, stepDelayMillis * 2L));
    }
    public int getStepDelayMillis() { return stepDelayMillis; }
    public boolean isRunning() { return running; }
    public boolean isPaused() { return paused; }
    public boolean isDeadlockDemo() { return !barrierTrainIds.isEmpty(); }
    boolean shouldAwaitFirstTrackBarrier(Train train) {
        return firstTrackBarrier != null && train != null && barrierTrainIds.contains(train.getId())
                && train.getRouteIndex() == 0;
    }
    void awaitFirstTrackBarrier() throws InterruptedException {
        CountDownLatch barrier = firstTrackBarrier;
        if (barrier != null) { barrier.countDown(); barrier.await(); }
    }
    public ScenarioType getScenarioType() { return scenarioType; }
    public String getScenarioDisplayName() { return scenarioDisplayName; }
    public Map<String, Train> getTrains() { return trains; }
    public Map<String, TrackSegment> getTracks() { return tracks; }
    public Map<String, Signal> getSignals() { return signals; }
    public List<String[]> getConnections() { return connections; }
    public ResourceManager getResourceManager() { return resourceManager; }
    public EventLogger getEventLogger() { return eventLogger; }
    public RecoveryEngine getRecoveryEngine() { return recoveryEngine; }
    public List<DeadlockCycle> getActiveDeadlocks() { return activeDeadlocks; }
    public DeadlockCycle getLastDetectedDeadlock() { return lastDetectedDeadlock; }
    public boolean isLastDetectedDeadlockResolved() { return lastDetectedDeadlockResolved; }
    public int getResolvedDeadlocks() { return resolvedDeadlocks.get(); }
    public int getCompletedTrains() { return completedTrains.get(); }
    public int getTotalTrains() { return trains.size(); }
    public long getActiveConflicts() { return resourceManager.getWaitForGraph().values().stream().filter(edges -> !edges.isEmpty()).count(); }

    @Override public synchronized void close() {
        stopExecutors("CLOSED");
        resourceManager.shutdown();
    }
}
