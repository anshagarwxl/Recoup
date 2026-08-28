package com.recoup.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoup.domain.DiagnosisSource;
import com.recoup.domain.FailureDiagnosis;
import com.recoup.domain.FailureType;
import com.recoup.domain.PaymentContext;
import com.recoup.domain.PaymentFailure;
import com.recoup.domain.PaymentMethod;
import com.recoup.domain.PlannedAction;
import com.recoup.domain.RecoveryActionType;
import com.recoup.domain.RecoveryPlan;
import com.recoup.domain.RecoveryStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private final PolicyEngine policyEngine = new PolicyEngine();
    private static final Instant FAILURE_TIME = Instant.parse("2026-08-25T12:00:00Z");

    @Test
    void hardDeclinePlanSchedulesStopImmediately() {
        PaymentFailure failure = new PaymentFailure(
                "pay_1", "ref_1", PaymentContext.ONE_TIME_CHECKOUT, 5000,
                PaymentMethod.CARD, FAILURE_TIME, "FRAUD_FLAGGED", "Card flagged as lost/stolen"
        );
        FailureDiagnosis diagnosis = new FailureDiagnosis(
                FailureType.HARD_DECLINE, BigDecimal.ONE, "Flagged deterministically", DiagnosisSource.GATEWAY_CODE
        );

        RecoveryPlan plan = policyEngine.createPlan(failure, diagnosis);

        assertNotNull(plan);
        assertEquals(RecoveryStatus.STOPPED, plan.status());
        assertEquals(1, plan.plannedActions().size());

        PlannedAction action = plan.plannedActions().get(0);
        assertEquals(RecoveryActionType.STOP_RECOVERY, action.actionType());
        assertEquals(FAILURE_TIME, action.scheduledFor());
        assertEquals(0L, action.costPaise());
        assertTrue(plan.stoppingRationale().contains("Terminal fraud"));
    }

    @Test
    void insufficientFundsSubscriptionPlanSchedulesReminderAndRetry() {
        PaymentFailure failure = new PaymentFailure(
                "pay_2", "ref_2", PaymentContext.SUBSCRIPTION_RENEWAL, 49900, // ₹499
                PaymentMethod.UPI, FAILURE_TIME, "INSUFFICIENT_FUNDS", "Low balance"
        );
        FailureDiagnosis diagnosis = new FailureDiagnosis(
                FailureType.INSUFFICIENT_FUNDS, BigDecimal.ONE, "Code matched", DiagnosisSource.GATEWAY_CODE
        );

        RecoveryPlan plan = policyEngine.createPlan(failure, diagnosis);

        assertNotNull(plan);
        assertEquals(RecoveryStatus.IN_PROGRESS, plan.status());
        assertEquals(2, plan.plannedActions().size());

        // UPI Reminder +1h, cost 100
        PlannedAction reminder = plan.plannedActions().get(0);
        assertEquals(RecoveryActionType.SEND_UPI_REMINDER, reminder.actionType());
        assertEquals(FAILURE_TIME.plusSeconds(3600), reminder.scheduledFor());
        assertEquals(100L, reminder.costPaise());

        // Payment Retry +24h, cost 500
        PlannedAction retry = plan.plannedActions().get(1);
        assertEquals(RecoveryActionType.RETRY_PAYMENT, retry.actionType());
        assertEquals(FAILURE_TIME.plusSeconds(86400), retry.scheduledFor());
        assertEquals(500L, retry.costPaise());
    }

    @Test
    void bankTechnicalErrorSchedulesTwoRetries() {
        PaymentFailure failure = new PaymentFailure(
                "pay_3", "ref_3", PaymentContext.B2B_RECEIVABLE, 200000, // ₹2000
                PaymentMethod.NETBANKING, FAILURE_TIME, "BANK_TECHNICAL_ERROR", "Bank down"
        );
        FailureDiagnosis diagnosis = new FailureDiagnosis(
                FailureType.BANK_TECHNICAL_ERROR, BigDecimal.ONE, "Code matched", DiagnosisSource.GATEWAY_CODE
        );

        RecoveryPlan plan = policyEngine.createPlan(failure, diagnosis);

        assertNotNull(plan);
        assertEquals(2, plan.plannedActions().size());

        PlannedAction retry1 = plan.plannedActions().get(0);
        assertEquals(RecoveryActionType.RETRY_PAYMENT, retry1.actionType());
        assertEquals(FAILURE_TIME.plusSeconds(3600), retry1.scheduledFor());
        assertEquals(500L, retry1.costPaise());

        PlannedAction retry2 = plan.plannedActions().get(1);
        assertEquals(RecoveryActionType.RETRY_PAYMENT, retry2.actionType());
        assertEquals(FAILURE_TIME.plusSeconds(21600), retry2.scheduledFor());
        assertEquals(500L, retry2.costPaise());
    }

    @Test
    void unknownSchedulesImmediateEscalation() {
        PaymentFailure failure = new PaymentFailure(
                "pay_4", "ref_4", PaymentContext.ONE_TIME_CHECKOUT, 15000,
                PaymentMethod.UPI, FAILURE_TIME, null, "Ambiguous text error"
        );
        FailureDiagnosis diagnosis = new FailureDiagnosis(
                FailureType.UNKNOWN, BigDecimal.ZERO, "Gemini fallback triggered", DiagnosisSource.MOCK_FALLBACK
        );

        RecoveryPlan plan = policyEngine.createPlan(failure, diagnosis);

        assertNotNull(plan);
        assertEquals(RecoveryStatus.STOPPED, plan.status());
        assertEquals(1, plan.plannedActions().size());

        PlannedAction escalation = plan.plannedActions().get(0);
        assertEquals(RecoveryActionType.ESCALATE_TO_ACCOUNT_MANAGER, escalation.actionType());
        assertEquals(FAILURE_TIME, escalation.scheduledFor());
        assertEquals(5000L, escalation.costPaise()); // ₹50 escalation cost
    }

    @Test
    void highValueCheckoutTriggersEscalationOverlay() {
        // Amount ₹15,000 (1,500,000 paise) triggers high-value escalation overlay
        PaymentFailure failure = new PaymentFailure(
                "pay_5", "ref_5", PaymentContext.ONE_TIME_CHECKOUT, 1_500_000L,
                PaymentMethod.UPI, FAILURE_TIME, "INSUFFICIENT_FUNDS", "Low balance"
        );
        FailureDiagnosis diagnosis = new FailureDiagnosis(
                FailureType.INSUFFICIENT_FUNDS, BigDecimal.ONE, "Code matched", DiagnosisSource.GATEWAY_CODE
        );

        RecoveryPlan plan = policyEngine.createPlan(failure, diagnosis);

        assertNotNull(plan);
        assertEquals(RecoveryStatus.IN_PROGRESS, plan.status());
        assertEquals(2, plan.plannedActions().size()); // 1 link + 1 overlay escalation

        // First action: Send link (+15m, cost 200)
        PlannedAction link = plan.plannedActions().get(0);
        assertEquals(RecoveryActionType.SEND_PAYMENT_LINK, link.actionType());
        assertEquals(200L, link.costPaise());

        // Overlay action: Escalation (+48h, cost 5000)
        PlannedAction escalation = plan.plannedActions().get(1);
        assertEquals(RecoveryActionType.ESCALATE_TO_ACCOUNT_MANAGER, escalation.actionType());
        assertEquals(FAILURE_TIME.plusSeconds(172800), escalation.scheduledFor());
        assertEquals(5000L, escalation.costPaise());
    }

    @Test
    void highValueUnknownPlanPreventsDuplicateEscalation() {
        // High-value Unknown plan already escalates at +0h. It should NOT append duplicate escalation at +48h.
        PaymentFailure failure = new PaymentFailure(
                "pay_6", "ref_6", PaymentContext.SUBSCRIPTION_RENEWAL, 2_000_000L, // ₹20,000
                PaymentMethod.UPI, FAILURE_TIME, null, "Ambiguous reason text"
        );
        FailureDiagnosis diagnosis = new FailureDiagnosis(
                FailureType.UNKNOWN, BigDecimal.ZERO, "Fallback active", DiagnosisSource.MOCK_FALLBACK
        );

        RecoveryPlan plan = policyEngine.createPlan(failure, diagnosis);

        assertNotNull(plan);
        assertEquals(1, plan.plannedActions().size()); // Only the immediate escalation, no duplicates
        PlannedAction escalation = plan.plannedActions().get(0);
        assertEquals(RecoveryActionType.ESCALATE_TO_ACCOUNT_MANAGER, escalation.actionType());
        assertEquals(FAILURE_TIME, escalation.scheduledFor());
        assertEquals(5000L, escalation.costPaise());
    }

    @Test
    void highValueHardDeclinePreventsEscalation() {
        // Hard decline must never escalate or schedule recovery actions, even if high value
        PaymentFailure failure = new PaymentFailure(
                "pay_7", "ref_7", PaymentContext.B2B_RECEIVABLE, 3_000_000L, // ₹30,000
                PaymentMethod.CARD, FAILURE_TIME, "FRAUD_FLAGGED", "Card stolen"
        );
        FailureDiagnosis diagnosis = new FailureDiagnosis(
                FailureType.HARD_DECLINE, BigDecimal.ONE, "Code matched", DiagnosisSource.GATEWAY_CODE
        );

        RecoveryPlan plan = policyEngine.createPlan(failure, diagnosis);

        assertNotNull(plan);
        assertEquals(RecoveryStatus.STOPPED, plan.status());
        assertEquals(1, plan.plannedActions().size());
        assertEquals(RecoveryActionType.STOP_RECOVERY, plan.plannedActions().get(0).actionType());
    }
}
