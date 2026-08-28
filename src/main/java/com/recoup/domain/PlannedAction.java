package com.recoup.domain;

import java.time.Instant;
import java.util.Objects;

public record PlannedAction(RecoveryActionType actionType, Instant scheduledFor, String rationale, long costPaise) {

    public PlannedAction {
        actionType = Objects.requireNonNull(actionType, "actionType must not be null");
        scheduledFor = Objects.requireNonNull(scheduledFor, "scheduledFor must not be null");
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("rationale must not be blank");
        }
        rationale = rationale.trim();
        if (costPaise < 0) {
            throw new IllegalArgumentException("costPaise must not be negative");
        }
    }
}
