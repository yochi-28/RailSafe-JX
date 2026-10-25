package com.railsafe.jx.simulation;

import com.railsafe.jx.recovery.RecoveryStrategy;
import com.railsafe.jx.util.EventLogger;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SimulationManagerTest {
    @Test void deterministicDemoCreatesAndResolvesDeadlock() throws Exception {
        EventLogger logger = new EventLogger();
        SimulationManager manager = new SimulationManager(logger);
        try {
            manager.setStepDelayMillis(80);
            manager.reset(ScenarioType.DEADLOCK_DEMO);
            manager.setRecoveryStrategy(RecoveryStrategy.REROUTE);
            manager.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
            boolean detected = false;
            while (System.nanoTime() < deadline && manager.isRunning()) {
                detected = logger.getRecentEvents(2000).stream().anyMatch(event -> event.type().equals("DEADLOCK_DETECTED"));
                if (detected && manager.getResolvedDeadlocks() > 0) break;
                Thread.sleep(40);
            }
            assertTrue(detected, "demo should produce a wait-for graph cycle");
            assertTrue(manager.getResolvedDeadlocks() > 0, "detector should initiate recovery");
            assertNotNull(manager.getLastDetectedDeadlock(), "latest detected cycle should remain available for the dashboard post-mortem");
            assertEquals(java.util.List.of("T1", "T2"), manager.getLastDetectedDeadlock().trainIds());
            assertTrue(manager.isLastDetectedDeadlockResolved(), "the demo cycle should be marked resolved after graph recheck");
            assertTrue(manager.getResourceManager().isOwnershipMapConsistent());
            assertTrue(manager.getResourceManager().isOwnershipConsistentWithTrains(manager.getTrains()));
        } finally { manager.close(); }
    }

    @Test void normalScenarioCompletesEveryTrain() throws Exception {
        EventLogger logger = new EventLogger();
        SimulationManager manager = new SimulationManager(logger);
        try {
            manager.setStepDelayMillis(60);
            manager.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline && manager.getCompletedTrains() < manager.getTotalTrains()) Thread.sleep(40);
            assertEquals(3, manager.getCompletedTrains());
            assertTrue(manager.getResourceManager().getOwnershipSnapshot().isEmpty());
            assertTrue(manager.getResourceManager().isOwnershipConsistentWithTrains(manager.getTrains()));
            assertFalse(logger.getRecentEvents(2000).stream().anyMatch(event -> event.type().equals("DEADLOCK_DETECTED")),
                    "normal scenario should complete without a deadlock cycle");
        } finally { manager.close(); }
    }
}
