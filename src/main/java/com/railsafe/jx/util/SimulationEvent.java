package com.railsafe.jx.util;

import java.time.Instant;

public record SimulationEvent(Instant timestamp, String type, String trainId, String message) {
    public SimulationEvent {
        if (timestamp == null) timestamp = Instant.now();
        if (type == null || type.isBlank()) type = "INFO";
        if (trainId == null) trainId = "SYSTEM";
        if (message == null) message = "";
    }
}
