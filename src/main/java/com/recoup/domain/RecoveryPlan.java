package com.recoup.domain;

import java.util.List;
import java.util.Objects;

public record RecoveryPlan(
        RecoveryStatus status,
        List<PlannedAction> plannedActions,
        String stoppingRationale) {

    public RecoveryPlan {
        status = Objects.requireNonNull(status, "status must not be null");
        plannedActions = List.copyOf(Objects.requireNonNull(plannedActions, "plannedActions must not be null"));
        if (plannedActions.isEmpty()) {
            throw new IllegalArgumentException("plannedActions must not be empty");
        }
        if (stoppingRationale == null || stoppingRationale.isBlank()) {
            throw new IllegalArgumentException("stoppingRationale must not be blank");
        }
        stoppingRationale = stoppingRationale.trim();
    }
}
