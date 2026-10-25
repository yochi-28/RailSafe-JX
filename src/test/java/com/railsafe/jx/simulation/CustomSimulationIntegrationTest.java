package com.railsafe.jx.simulation;

import com.railsafe.jx.recovery.RecoveryStrategy;
import com.railsafe.jx.util.EventLogger;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomSimulationIntegrationTest {
    @Test
    void userDefinedCrossedRoutesCreateAndResolveRealDeadlock() throws Exception {
        EventLogger logger = new EventLogger();
        ScenarioFactory.ScenarioData data = CustomScenarioFactory.create(
                List.of(
                        new CustomScenarioFactory.ResourceDefinition("A", "TRACK"),
                        new CustomScenarioFactory.ResourceDefinition("B", "TRACK"),
                        new CustomScenarioFactory.ResourceDefinition("C", "TRACK"),
                        new CustomScenarioFactory.ResourceDefinition("D", "TRACK")),
                List.of(
                        new CustomScenarioFactory.TrainDefinition("T1", "Custom Express", 3,
                                List.of("A", "B", "C"), List.of(List.of("A", "D", "C"))),
                        new CustomScenarioFactory.TrainDefinition("T2", "Custom Local", 1,
                                List.of("B", "A", "D"), List.of(List.of("B", "D")))),
                List.of(new String[]{"A", "B"}, new String[]{"B", "C"},
                        new String[]{"A", "D"}, new String[]{"D", "C"}, new String[]{"B", "D"}));
        SimulationManager manager = new SimulationManager(logger);
        try {
            manager.setStepDelayMillis(60);
            manager.resetCustom(data, "Test Custom Deadlock", true);
            manager.setRecoveryStrategy(RecoveryStrategy.REROUTE);
            manager.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline && manager.isRunning()) {
                boolean detected = logger.getRecentEvents(2000).stream().anyMatch(event -> event.type().equals("DEADLOCK_DETECTED"));
                if (detected && manager.getResolvedDeadlocks() > 0 && manager.getCompletedTrains() == 2) break;
                Thread.sleep(30);
            }
            assertTrue(logger.getRecentEvents(2000).stream().anyMatch(event -> event.type().equals("DEADLOCK_DETECTED")));
            assertTrue(manager.getResolvedDeadlocks() >= 1);
            assertEquals(2, manager.getCompletedTrains());
            assertTrue(manager.getResourceManager().getOwnershipSnapshot().isEmpty());
            assertTrue(manager.getResourceManager().isOwnershipConsistentWithTrains(manager.getTrains()));
        } finally { manager.close(); logger.close(); }
    }
    @Test
    void userDefinedDisconnectedRoutesCompleteWithoutDeadlock() throws Exception {
        EventLogger logger = new EventLogger();
        var data = CustomScenarioFactory.create(
                List.of(
                        new CustomScenarioFactory.ResourceDefinition("A", "TRACK"),
                        new CustomScenarioFactory.ResourceDefinition("B", "TRACK"),
                        new CustomScenarioFactory.ResourceDefinition("C", "PLATFORM"),
                        new CustomScenarioFactory.ResourceDefinition("D", "TRACK"),
                        new CustomScenarioFactory.ResourceDefinition("E", "TRACK"),
                        new CustomScenarioFactory.ResourceDefinition("F", "PLATFORM")),
                List.of(
                        new CustomScenarioFactory.TrainDefinition("T1", "North Route", 3,
                                List.of("A", "B", "C"), List.of()),
                        new CustomScenarioFactory.TrainDefinition("T2", "South Route", 2,
                                List.of("D", "E", "F"), List.of())),
                List.of(new String[]{"A", "B"}, new String[]{"B", "C"},
                        new String[]{"D", "E"}, new String[]{"E", "F"}));
        SimulationManager manager = new SimulationManager(logger);
        try {
            manager.setStepDelayMillis(50);
            manager.resetCustom(data, "Test Custom Deadlock-Free Scenario", false);
            manager.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && manager.isRunning()) Thread.sleep(20);

            assertEquals(2, manager.getCompletedTrains());
            assertTrue(logger.getRecentEvents(2000).stream().noneMatch(event -> event.type().equals("DEADLOCK_DETECTED")));
            assertTrue(manager.getResourceManager().getOwnershipSnapshot().isEmpty());
            assertTrue(manager.getResourceManager().isOwnershipConsistentWithTrains(manager.getTrains()));
        } finally {
            manager.close();
            logger.close();
        }
    }

}
