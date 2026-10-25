package com.railsafe.jx.database;

import com.railsafe.jx.util.EventSink;
import com.railsafe.jx.util.SimulationEvent;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Serial background writer. JDBC latency and failure do not run on train worker threads. */
public final class AsyncDatabaseLogger implements EventSink {
    private final DatabaseService service;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "railsafe-jdbc-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<String> diagnostic;
    private volatile long activeRunId = -1;

    public AsyncDatabaseLogger(DatabaseService service, Consumer<String> diagnostic) {
        this.service = service;
        this.diagnostic = diagnostic == null ? ignored -> { } : diagnostic;
    }

    @Override public void startRun(String scenarioName) {
        if (!service.isConfigured()) return;
        writer.execute(() -> {
            try {
                activeRunId = service.startRun(scenarioName);
                diagnostic.accept("JDBC logging enabled for run " + activeRunId);
            } catch (SQLException | RuntimeException ex) {
                activeRunId = -1;
                diagnostic.accept("JDBC unavailable; continuing in memory: " + ex.getMessage());
            }
        });
    }

    @Override public void accept(SimulationEvent event) {
        if (!service.isConfigured()) return;
        writer.execute(() -> {
            long runId = activeRunId;
            if (runId <= 0) return;
            try {
                service.recordEvent(runId, event);
                if ("DEADLOCK_DETECTED".equals(event.type())) service.recordDeadlock(runId, event);
                if ("DEADLOCK_RESOLVED".equals(event.type())) service.resolveDeadlocks(runId, event.message());
                if ("RECOVERY_ACTION".equals(event.type())) service.recordRecovery(runId, event);
            } catch (SQLException | RuntimeException ex) {
                diagnostic.accept("Database write failed; simulation continues: " + ex.getMessage());
            }
        });
    }

    @Override public void finishRun(String outcome) {
        if (!service.isConfigured()) return;
        writer.execute(() -> {
            long runId = activeRunId;
            if (runId <= 0) return;
            try { service.finishRun(runId, outcome); }
            catch (SQLException | RuntimeException ex) { diagnostic.accept("Could not finish DB run: " + ex.getMessage()); }
        });
    }

    public List<RunSummary> recentRuns(int limit) throws SQLException { return service.recentRuns(limit); }

    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
