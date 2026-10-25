package com.railsafe.jx.database;

import java.time.LocalDateTime;

public record RunSummary(long runId, String scenarioName, LocalDateTime startedAt,
                         LocalDateTime endedAt, String outcome) { }
