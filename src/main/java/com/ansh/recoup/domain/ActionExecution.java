package com.ansh.recoup.domain;

import java.time.Instant;
import java.util.Objects;

public record ActionExecution(
        PlannedAction plannedAction,
        ActionResult result,
        Instant executedAt,
        long costPaise,
        String outcomeNote) {

    public ActionExecution {
        plannedAction = Objects.requireNonNull(plannedAction, "plannedAction must not be null");
        result = Objects.requireNonNull(result, "result must not be null");
        executedAt = Objects.requireNonNull(executedAt, "executedAt must not be null");
        if (executedAt.isBefore(plannedAction.scheduledFor())) {
            throw new IllegalArgumentException("executedAt cannot be before scheduledFor");
        }
        if (costPaise < 0) {
            throw new IllegalArgumentException("costPaise must not be negative");
        }
        if (outcomeNote == null || outcomeNote.isBlank()) {
            throw new IllegalArgumentException("outcomeNote must not be blank");
        }
        outcomeNote = outcomeNote.trim();
    }
}
