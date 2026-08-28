package com.recoup.domain;

import java.time.Instant;
import java.util.Objects;

public record AuditEvent(Instant occurredAt, AuditEventType type, String message) {

    public AuditEvent {
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        message = message.trim();
    }
}
