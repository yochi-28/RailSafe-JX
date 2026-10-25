CREATE DATABASE IF NOT EXISTS railsafe_jx;
USE railsafe_jx;

CREATE TABLE IF NOT EXISTS simulation_runs (
    run_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    scenario_name VARCHAR(80) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at TIMESTAMP(3) NULL,
    outcome VARCHAR(40) NOT NULL DEFAULT 'RUNNING'
);

CREATE TABLE IF NOT EXISTS train_events (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    event_time TIMESTAMP(3) NOT NULL,
    train_id VARCHAR(40) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    message VARCHAR(1200) NOT NULL,
    CONSTRAINT fk_train_events_run FOREIGN KEY (run_id) REFERENCES simulation_runs(run_id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS deadlock_events (
    deadlock_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    detected_at TIMESTAMP(3) NOT NULL,
    cycle_train_ids VARCHAR(500) NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolution_summary VARCHAR(1200) NULL,
    CONSTRAINT fk_deadlock_events_run FOREIGN KEY (run_id) REFERENCES simulation_runs(run_id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS recovery_actions (
    action_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    action_time TIMESTAMP(3) NOT NULL,
    train_id VARCHAR(40) NOT NULL,
    strategy VARCHAR(30) NOT NULL,
    reason VARCHAR(1200) NOT NULL,
    outcome VARCHAR(1200) NOT NULL,
    CONSTRAINT fk_recovery_actions_run FOREIGN KEY (run_id) REFERENCES simulation_runs(run_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_train_events_run_time ON train_events(run_id, event_time);
CREATE INDEX idx_deadlock_events_run_time ON deadlock_events(run_id, detected_at);
CREATE INDEX idx_recovery_actions_run_time ON recovery_actions(run_id, action_time);
