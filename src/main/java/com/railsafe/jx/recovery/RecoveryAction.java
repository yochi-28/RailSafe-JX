package com.railsafe.jx.recovery;

import java.time.Instant;

public record RecoveryAction(Instant timestamp, String trainId, RecoveryStrategy strategy,
                             String reason, String outcome, boolean successful) {
    public RecoveryAction {
        if (timestamp == null) timestamp = Instant.now();
    }
}
