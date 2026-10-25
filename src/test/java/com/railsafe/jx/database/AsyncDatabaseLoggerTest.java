package com.railsafe.jx.database;

import com.railsafe.jx.util.SimulationEvent;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class AsyncDatabaseLoggerTest {
    @Test void queuedEventsArePersistedUsingServiceDouble() {
        FakeDatabaseService fake = new FakeDatabaseService();
        AsyncDatabaseLogger logger = new AsyncDatabaseLogger(fake, ignored -> { });
        logger.startRun("test scenario");
        logger.accept(new SimulationEvent(Instant.now(), "TRAIN_MOVED", "T1", "Entered A"));
        logger.accept(new SimulationEvent(Instant.now(), "RECOVERY_ACTION", "T1", "REROUTE | SUCCESS | reason | outcome"));
        logger.finishRun("COMPLETED");
        logger.close();
        assertEquals(42L, fake.startedRunId);
        assertEquals(2, fake.events.size());
        assertEquals(1, fake.recoveryCount);
        assertEquals("COMPLETED", fake.outcome);
    }

    @Test void jdbcServiceClearlyReportsMissingConfiguration() {
        JdbcDatabaseService service = new JdbcDatabaseService("", "", "");
        assertFalse(service.isConfigured());
        assertThrows(SQLException.class, () -> service.recentRuns(10));
    }

    private static final class FakeDatabaseService implements DatabaseService {
        private final List<SimulationEvent> events = new CopyOnWriteArrayList<>();
        private long startedRunId;
        private int recoveryCount;
        private String outcome;
        @Override public boolean isConfigured() { return true; }
        @Override public long startRun(String scenarioName) { startedRunId = 42; return 42; }
        @Override public void finishRun(long runId, String outcome) { this.outcome = outcome; }
        @Override public void recordEvent(long runId, SimulationEvent event) { events.add(event); }
        @Override public void recordDeadlock(long runId, SimulationEvent event) { }
        @Override public void resolveDeadlocks(long runId, String summary) { }
        @Override public void recordRecovery(long runId, SimulationEvent event) { recoveryCount++; }
        @Override public List<RunSummary> recentRuns(int limit) { return new ArrayList<>(); }
    }
}
