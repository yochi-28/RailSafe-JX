package com.railsafe.jx.database;

import com.railsafe.jx.util.SimulationEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Properties;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC persistence for RailSafe-JX.
 *
 * Explicit RAILSAFE_DB_* environment variables take priority. If the platform-specific local setup script has created
 * ~/.railsafe-jx/mysql.properties, that local MySQL configuration is used next. H2 remains a fallback for tests or users
 * who intentionally run without a MySQL setup. Missing tables are created on
 * startup for either backend.
 */
public final class JdbcDatabaseService implements DatabaseService {
    private final String url;
    private final String user;
    private final String password;
    private final Object schemaLock = new Object();
    private volatile boolean schemaInitialized;

    /** Choose explicit environment settings, saved local MySQL settings, or H2 fallback on any OS. */
    public JdbcDatabaseService() {
        String externalUrl = System.getenv("RAILSAFE_DB_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            this.url = externalUrl.trim();
            this.user = valueOrEmpty(System.getenv("RAILSAFE_DB_USER"));
            this.password = valueOrEmpty(System.getenv("RAILSAFE_DB_PASSWORD"));
            return;
        }

        Properties saved = loadLocalMysqlSettings();
        String savedUrl = saved.getProperty("url", "").trim();
        if (!savedUrl.isBlank()) {
            this.url = savedUrl;
            this.user = preferNonBlank(System.getenv("RAILSAFE_DB_USER"), saved.getProperty("user", ""));
            this.password = preferNonBlank(System.getenv("RAILSAFE_DB_PASSWORD"), saved.getProperty("password", ""));
        } else {
            this.url = defaultEmbeddedUrl();
            this.user = "sa";
            this.password = "";
        }
    }

    private static Properties loadLocalMysqlSettings() {
        Path configFile = Path.of(System.getProperty("user.home"), ".railsafe-jx", "mysql.properties");
        Properties properties = new Properties();
        if (!Files.isRegularFile(configFile)) return properties;
        try (var input = Files.newInputStream(configFile)) {
            properties.load(input);
            return properties;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read local MySQL settings at " + configFile, ex);
        }
    }

    private static String preferNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : valueOrEmpty(fallback);
    }

    /** Explicit settings constructor retained for configuration and unit tests. */
    public JdbcDatabaseService(String url, String user, String password) {
        this.url = url == null ? "" : url.trim();
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
    }

    private static String defaultEmbeddedUrl() {
        Path dataDirectory = Path.of(System.getProperty("user.home"), ".railsafe-jx");
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create RailSafe-JX data directory: " + dataDirectory, ex);
        }
        String databasePath = dataDirectory.resolve("railsafe-history").toAbsolutePath().toString().replace('\\', '/');
        return "jdbc:h2:file:" + databasePath + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override public boolean isConfigured() { return !url.isBlank(); }

    /** Opens the database and creates missing tables on app startup. Safe to call again. */
    public void initialize() throws SQLException {
        if (!isConfigured()) return;
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            ensureSchema(connection);
        }
    }

    private Connection connect() throws SQLException {
        if (!isConfigured()) throw new SQLException("Database is not configured");
        Connection connection = DriverManager.getConnection(url, user, password);
        try {
            ensureSchema(connection);
            return connection;
        } catch (SQLException | RuntimeException ex) {
            try { connection.close(); } catch (SQLException closeError) { ex.addSuppressed(closeError); }
            throw ex;
        }
    }

    /** DDL deliberately avoids vendor-specific index syntax, allowing H2 and MySQL. */
    private void ensureSchema(Connection connection) throws SQLException {
        if (schemaInitialized) return;
        synchronized (schemaLock) {
            if (schemaInitialized) return;
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS simulation_runs (
                        run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        scenario_name VARCHAR(80) NOT NULL,
                        started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        ended_at TIMESTAMP(3) NULL,
                        outcome VARCHAR(40) NOT NULL DEFAULT 'RUNNING'
                    )
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS train_events (
                        event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        run_id BIGINT NOT NULL,
                        event_time TIMESTAMP(3) NOT NULL,
                        train_id VARCHAR(40) NOT NULL,
                        event_type VARCHAR(50) NOT NULL,
                        message VARCHAR(1200) NOT NULL,
                        CONSTRAINT fk_train_events_run FOREIGN KEY (run_id)
                            REFERENCES simulation_runs(run_id) ON DELETE CASCADE
                    )
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS deadlock_events (
                        deadlock_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        run_id BIGINT NOT NULL,
                        detected_at TIMESTAMP(3) NOT NULL,
                        cycle_train_ids VARCHAR(500) NOT NULL,
                        resolved BOOLEAN NOT NULL DEFAULT FALSE,
                        resolution_summary VARCHAR(1200) NULL,
                        CONSTRAINT fk_deadlock_events_run FOREIGN KEY (run_id)
                            REFERENCES simulation_runs(run_id) ON DELETE CASCADE
                    )
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS recovery_actions (
                        action_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        run_id BIGINT NOT NULL,
                        action_time TIMESTAMP(3) NOT NULL,
                        train_id VARCHAR(40) NOT NULL,
                        strategy VARCHAR(30) NOT NULL,
                        reason VARCHAR(1200) NOT NULL,
                        outcome VARCHAR(1200) NOT NULL,
                        CONSTRAINT fk_recovery_actions_run FOREIGN KEY (run_id)
                            REFERENCES simulation_runs(run_id) ON DELETE CASCADE
                    )
                    """);
            }
            schemaInitialized = true;
        }
    }

    @Override public long startRun(String scenarioName) throws SQLException {
        String sql = "INSERT INTO simulation_runs (scenario_name, outcome) VALUES (?, 'RUNNING')";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, scenarioName);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("Could not obtain simulation run ID");
    }

    @Override public void finishRun(long runId, String outcome) throws SQLException {
        String sql = "UPDATE simulation_runs SET ended_at = CURRENT_TIMESTAMP(3), outcome = ? WHERE run_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, outcome);
            statement.setLong(2, runId);
            statement.executeUpdate();
        }
    }

    @Override public void recordEvent(long runId, SimulationEvent event) throws SQLException {
        String sql = "INSERT INTO train_events (run_id, event_time, train_id, event_type, message) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setTimestamp(2, Timestamp.from(event.timestamp()));
            statement.setString(3, event.trainId());
            statement.setString(4, event.type());
            statement.setString(5, truncate(event.message(), 1200));
            statement.executeUpdate();
        }
    }

    @Override public void recordDeadlock(long runId, SimulationEvent event) throws SQLException {
        String sql = "INSERT INTO deadlock_events (run_id, detected_at, cycle_train_ids, resolved) VALUES (?, ?, ?, FALSE)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setTimestamp(2, Timestamp.from(event.timestamp()));
            statement.setString(3, truncate(event.message(), 500));
            statement.executeUpdate();
        }
    }

    @Override public void resolveDeadlocks(long runId, String summary) throws SQLException {
        String sql = "UPDATE deadlock_events SET resolved = TRUE, resolution_summary = ? WHERE run_id = ? AND resolved = FALSE";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, truncate(summary, 1200));
            statement.setLong(2, runId);
            statement.executeUpdate();
        }
    }

    @Override public void recordRecovery(long runId, SimulationEvent event) throws SQLException {
        String[] parts = event.message().split("\\s*\\|\\s*", 4);
        String strategy = parts.length > 0 ? parts[0] : "UNKNOWN";
        String reason = parts.length > 2 ? parts[2] : event.message();
        String outcome = parts.length > 3 ? parts[3] : event.message();
        String sql = "INSERT INTO recovery_actions (run_id, action_time, train_id, strategy, reason, outcome) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setTimestamp(2, Timestamp.from(event.timestamp()));
            statement.setString(3, event.trainId());
            statement.setString(4, truncate(strategy, 30));
            statement.setString(5, truncate(reason, 1200));
            statement.setString(6, truncate(outcome, 1200));
            statement.executeUpdate();
        }
    }

    @Override public List<RunSummary> recentRuns(int limit) throws SQLException {
        String sql = "SELECT run_id, scenario_name, started_at, ended_at, outcome FROM simulation_runs ORDER BY started_at DESC LIMIT ?";
        List<RunSummary> results = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, Math.min(limit, 200)));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Timestamp started = resultSet.getTimestamp("started_at");
                    Timestamp ended = resultSet.getTimestamp("ended_at");
                    results.add(new RunSummary(resultSet.getLong("run_id"), resultSet.getString("scenario_name"),
                            toLocal(started), toLocal(ended), resultSet.getString("outcome")));
                }
            }
        }
        return List.copyOf(results);
    }

    private static LocalDateTime toLocal(Timestamp value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault());
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
