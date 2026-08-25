package com.ansh.recoup.domain;

import java.time.Instant;
import java.util.Objects;

public record PlannedAction(RecoveryActionType actionType, Instant scheduledFor, String rationale) {

    public PlannedAction {
        actionType = Objects.requireNonNull(actionType, "actionType must not be null");
        scheduledFor = Objects.requireNonNull(scheduledFor, "scheduledFor must not be null");
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("rationale must not be blank");
        }
        rationale = rationale.trim();
    }
}
