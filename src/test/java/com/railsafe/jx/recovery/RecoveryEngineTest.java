package com.railsafe.jx.recovery;

import com.railsafe.jx.deadlock.DeadlockCycle;
import com.railsafe.jx.model.Train;
import com.railsafe.jx.model.TrainStatus;
import com.railsafe.jx.resource.ResourceManager;
import com.railsafe.jx.util.EventLogger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryEngineTest {
    @Test void rerouteUsesAlternateRoute() {
        Fixture f = fixture(true, true, false);
        try {
            f.engine.setStrategy(RecoveryStrategy.REROUTE);
            assertTrue(f.engine.resolve(f.cycle));
            assertTrue(f.t2.getRoute().equals(List.of("B", "D")) || f.t1.getRoute().equals(List.of("A", "C")));
            assertTrue(f.engine.getActions().stream().anyMatch(a -> a.strategy() == RecoveryStrategy.REROUTE && a.successful()));
            assertTrue(f.resources.isOwnershipMapConsistent());
        } finally { f.close(); }
    }

    @Test void delayMovesTrainToReservedSidingBeforeReleasingTrack() {
        Fixture f = fixture(false, false, false);
        try {
            f.engine.setStrategy(RecoveryStrategy.DELAY);
            assertTrue(f.engine.resolve(f.cycle));
            Train victim = f.t1.getPriority() <= f.t2.getPriority() ? f.t1 : f.t2;
            assertTrue(victim.getCurrentSegment().startsWith("S"));
            assertEquals(victim.getId(), f.resources.ownerOf(victim.getCurrentSegment()));
            assertEquals(TrainStatus.DELAYED, victim.getStatus());
            assertTrue(f.resources.ownerOf(victim.getId().equals("T1") ? "A" : "B") == null);
        } finally { f.close(); }
    }

    @Test void releaseRollsBackToPreviousSafeSegmentWhenAvailable() {
        EventLogger logger = new EventLogger();
        Train t1 = new Train("T1", "Low priority", 1, List.of("P", "A", "B", "C"), List.of(), "S1");
        Train t2 = new Train("T2", "High priority", 5, List.of("B", "A", "D"), List.of(), "S2");
        ResourceManager resources = new ResourceManager(logger);
        assertTrue(resources.tryAcquire("T1", "P"));
        t1.moveToSegment("P");
        assertTrue(resources.release("T1", "P"));
        assertTrue(resources.tryAcquire("T1", "A"));
        t1.moveToSegment("A");
        assertTrue(resources.tryAcquire("T2", "B"));
        t2.moveToSegment("B");
        RecoveryEngine engine = new RecoveryEngine(Map.of("T1", t1, "T2", t2), resources, logger, ignored -> { });
        try {
            engine.setStrategy(RecoveryStrategy.RELEASE);
            assertTrue(engine.resolve(new DeadlockCycle(List.of("T1", "T2"))));
            assertEquals("P", t1.getCurrentSegment());
            assertEquals("T1", resources.ownerOf("P"));
            assertNull(resources.ownerOf("A"));
            assertTrue(engine.getActions().stream().anyMatch(a -> a.strategy() == RecoveryStrategy.RELEASE && a.successful()));
        } finally { engine.close(); resources.shutdown(); }
    }

    @Test void missingAlternateRouteUsesSafeFallback() {
        Fixture f = fixture(false, false, false);
        try {
            f.engine.setStrategy(RecoveryStrategy.REROUTE);
            assertTrue(f.engine.resolve(f.cycle), "failed reroute should fall back to safe release or delay");
            assertTrue(f.engine.getActions().stream().anyMatch(a -> a.strategy() == RecoveryStrategy.REROUTE && !a.successful()));
            assertTrue(f.engine.getActions().stream().anyMatch(RecoveryAction::successful));
        } finally { f.close(); }
    }

    @Test void noPositionOrAlternateRouteFailsSafelyInsteadOfInventingOwnership() {
        EventLogger logger = new EventLogger();
        Train t1 = new Train("T1", "One", 1, List.of("A", "B"), List.of(), "S1");
        Train t2 = new Train("T2", "Two", 2, List.of("B", "A"), List.of(), "S2");
        ResourceManager resources = new ResourceManager(logger);
        RecoveryEngine engine = new RecoveryEngine(Map.of("T1", t1, "T2", t2), resources, logger, ignored -> { });
        try {
            engine.setStrategy(RecoveryStrategy.AUTO);
            assertFalse(engine.resolve(new DeadlockCycle(List.of("T1", "T2"))));
            assertTrue(resources.getOwnershipSnapshot().isEmpty());
            assertTrue(engine.getActions().stream().noneMatch(RecoveryAction::successful));
        } finally { engine.close(); resources.shutdown(); }
    }

    private static Fixture fixture(boolean t1Alt, boolean t2Alt, boolean unused) {
        EventLogger logger = new EventLogger();
        Train t1 = new Train("T1", "Coastal", 3, List.of("A", "B", "C"), t1Alt ? List.of(List.of("A", "C")) : List.of(), "S1");
        Train t2 = new Train("T2", "City", 1, List.of("B", "A", "D"), t2Alt ? List.of(List.of("B", "D")) : List.of(), "S2");
        ResourceManager resources = new ResourceManager(logger);
        assertTrue(resources.tryAcquire("T1", "A"));
        t1.moveToSegment("A");
        assertTrue(resources.tryAcquire("T2", "B"));
        t2.moveToSegment("B");
        RecoveryEngine engine = new RecoveryEngine(Map.of("T1", t1, "T2", t2), resources, logger, ignored -> { });
        return new Fixture(logger, resources, t1, t2, engine, new DeadlockCycle(List.of("T1", "T2")));
    }

    private record Fixture(EventLogger logger, ResourceManager resources, Train t1, Train t2,
                           RecoveryEngine engine, DeadlockCycle cycle) {
        void close() { engine.close(); resources.shutdown(); }
    }
}
