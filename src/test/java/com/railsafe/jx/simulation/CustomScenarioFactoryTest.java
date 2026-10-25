package com.railsafe.jx.simulation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomScenarioFactoryTest {
    @Test
    void createsCustomTracksPlatformsSignalsAndPrivateSidings() {
        ScenarioFactory.ScenarioData data = CustomScenarioFactory.create(
                List.of(new CustomScenarioFactory.ResourceDefinition("A", "TRACK"),
                        new CustomScenarioFactory.ResourceDefinition("P1", "PLATFORM")),
                List.of(new CustomScenarioFactory.TrainDefinition("T1", "Test Train", 2,
                        List.of("A", "P1"), List.of())));

        assertTrue(data.tracks().containsKey("A"));
        assertTrue(data.tracks().containsKey("P1"));
        assertTrue(data.tracks().containsKey(data.trains().get("T1").getSafeSidingId()));
        assertTrue(data.signals().containsKey("SIG-A"));
        assertEquals("T1", data.trains().get("T1").getId());
        assertFalse(data.connections().isEmpty());
    }

    @Test
    void rejectsRoutesThatReferenceMissingResources() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CustomScenarioFactory.create(
                        List.of(new CustomScenarioFactory.ResourceDefinition("A", "TRACK")),
                        List.of(new CustomScenarioFactory.TrainDefinition("T1", "Test Train", 2,
                                List.of("A", "MISSING"), List.of()))));
        assertTrue(ex.getMessage().contains("missing TRACK/PLATFORM node"));
    }

    @Test
    void rejectsRouteThatUsesAnUnconnectedTrackPair() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CustomScenarioFactory.create(
                        List.of(new CustomScenarioFactory.ResourceDefinition("A", "TRACK"),
                                new CustomScenarioFactory.ResourceDefinition("B", "TRACK"),
                                new CustomScenarioFactory.ResourceDefinition("C", "PLATFORM")),
                        List.of(new CustomScenarioFactory.TrainDefinition("T1", "Test Train", 2,
                                List.of("A", "B", "C"), List.of())),
                        List.<String[]>of(new String[]{"A", "B"})));
        assertTrue(ex.getMessage().contains("no physical connection exists"));
    }

    @Test
    void rejectsAlternateRouteWithDifferentDestination() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CustomScenarioFactory.create(
                        List.of(new CustomScenarioFactory.ResourceDefinition("A", "TRACK"),
                                new CustomScenarioFactory.ResourceDefinition("B", "TRACK"),
                                new CustomScenarioFactory.ResourceDefinition("C", "TRACK")),
                        List.of(new CustomScenarioFactory.TrainDefinition("T1", "Test Train", 2,
                                List.of("A", "B"), List.of(List.of("A", "C"))))));
        assertTrue(ex.getMessage().contains("start and finish"));
    }
}
