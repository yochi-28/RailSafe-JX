package com.railsafe.jx.database;

import com.railsafe.jx.util.SimulationEvent;

import java.sql.SQLException;
import java.util.List;

/** Persistence boundary kept separate from simulation business logic. */
public interface DatabaseService {
    boolean isConfigured();
    long startRun(String scenarioName) throws SQLException;
    void finishRun(long runId, String outcome) throws SQLException;
    void recordEvent(long runId, SimulationEvent event) throws SQLException;
    void recordDeadlock(long runId, SimulationEvent event) throws SQLException;
    void resolveDeadlocks(long runId, String summary) throws SQLException;
    void recordRecovery(long runId, SimulationEvent event) throws SQLException;
    List<RunSummary> recentRuns(int limit) throws SQLException;
}
