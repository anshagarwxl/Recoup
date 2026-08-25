package com.ansh.recoup.domain;

import java.time.Instant;
import java.util.Objects;

public record PaymentFailure(
        String paymentId,
        String merchantReference,
        PaymentContext context,
        long amountPaise,
        PaymentMethod paymentMethod,
        Instant failedAt,
        String gatewayFailureCode,
        String gatewayFailureReason) {

    public PaymentFailure {
        paymentId = required(paymentId, "paymentId");
        merchantReference = required(merchantReference, "merchantReference");
        context = Objects.requireNonNull(context, "context must not be null");
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be positive");
        }
        paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
        failedAt = Objects.requireNonNull(failedAt, "failedAt must not be null");
        gatewayFailureCode = optional(gatewayFailureCode);
        gatewayFailureReason = required(gatewayFailureReason, "gatewayFailureReason");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
