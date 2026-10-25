package com.railsafe.jx.deadlock;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeadlockDetectorTest {
    private final DeadlockDetector detector = new DeadlockDetector();

    @Test void detectsTwoTrainCycle() {
        var cycles = detector.findCycles(Map.of("T1", Set.of("T2"), "T2", Set.of("T1")));
        assertEquals(1, cycles.size());
        assertEquals(Set.of("T1", "T2"), Set.copyOf(cycles.getFirst().trainIds()));
    }

    @Test void ordinaryWaitingChainIsNotADeadlock() {
        var graph = Map.of("T1", Set.of("T2"), "T2", Set.of("T3"), "T3", Set.<String>of());
        assertTrue(detector.findCycles(graph).isEmpty());
    }

    @Test void findsCycleWithTailWithoutIncludingTail() {
        var graph = Map.of("T0", Set.of("T1"), "T1", Set.of("T2"), "T2", Set.of("T1"));
        var cycles = detector.findCycles(graph);
        assertEquals(1, cycles.size());
        assertFalse(cycles.getFirst().trainIds().contains("T0"));
    }
}
