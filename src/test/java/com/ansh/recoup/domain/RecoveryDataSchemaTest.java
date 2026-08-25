package com.ansh.recoup.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryDataSchemaTest {

    private static final Instant FAILURE_TIME = Instant.parse("2026-08-25T08:00:00Z");

    @Test
    void acceptsAnAuditableRecoveryCase() {
        PlannedAction retry = new PlannedAction(
                RecoveryActionType.RETRY_PAYMENT, FAILURE_TIME.plusSeconds(3_600), "Retry after the bank outage window");

        assertDoesNotThrow(() -> new RecoveryCase(
                failure(),
                new FailureDiagnosis(FailureType.BANK_TECHNICAL_ERROR, new BigDecimal("0.95"),
                        "Gateway code BAD_GATEWAY", DiagnosisSource.GATEWAY_CODE),
                new RecoveryPlan(RecoveryStatus.IN_PROGRESS, List.of(retry), "Stop after the single bounded retry"),
                List.of(new ActionExecution(retry, ActionResult.FAILED, FAILURE_TIME.plusSeconds(3_600), 0,
                        "Bank remained unavailable")),
                List.of(new AuditEvent(FAILURE_TIME, AuditEventType.FAILURE_RECORDED, "Payment failure imported"))));
    }

    @Test
    void rejectsInvalidMoneyAndPrematureExecution() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentFailure(
                "pay_1", "order_1", PaymentContext.ONE_TIME_CHECKOUT, 0, PaymentMethod.UPI,
                FAILURE_TIME, null, "Timed out"));

        PlannedAction action = new PlannedAction(
                RecoveryActionType.RETRY_PAYMENT, FAILURE_TIME.plusSeconds(60), "One retry is allowed");
        assertThrows(IllegalArgumentException.class, () -> new ActionExecution(
                action, ActionResult.FAILED, FAILURE_TIME, 0, "Too early"));
    }

    private PaymentFailure failure() {
        return new PaymentFailure("pay_123", "order_123", PaymentContext.SUBSCRIPTION_RENEWAL, 49_900,
                PaymentMethod.UPI, FAILURE_TIME, "BAD_GATEWAY", "The bank could not complete the payment");
    }
}
