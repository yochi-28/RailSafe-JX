package com.railsafe.jx.database;

import com.railsafe.jx.util.SimulationEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbcDatabaseServiceTest {
    @Test void createsSchemaAndPersistsRunWithoutManualSqlSetup() throws Exception {
        JdbcDatabaseService service = new JdbcDatabaseService(
                "jdbc:h2:mem:railsafe_autosetup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");

        // A read on a brand-new database must create the tables automatically.
        assertTrue(service.recentRuns(10).isEmpty());

        long runId = service.startRun("AUTO_DB_TEST");
        service.recordEvent(runId, new SimulationEvent(Instant.now(), "TRAIN_COMPLETED", "T1", "Reached destination"));
        service.finishRun(runId, "COMPLETED");

        List<RunSummary> runs = service.recentRuns(10);
        assertEquals(1, runs.size());
        assertEquals(runId, runs.get(0).runId());
        assertEquals("AUTO_DB_TEST", runs.get(0).scenarioName());
        assertEquals("COMPLETED", runs.get(0).outcome());
    }
}
