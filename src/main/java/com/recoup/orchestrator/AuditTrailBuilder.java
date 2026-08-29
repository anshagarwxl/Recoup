package com.recoup.orchestrator;

import com.recoup.domain.ActionExecution;
import com.recoup.domain.ActionResult;
import com.recoup.domain.AuditEvent;
import com.recoup.domain.AuditEventType;
import com.recoup.domain.FailureDiagnosis;
import com.recoup.domain.PaymentFailure;
import com.recoup.domain.RecoveryPlan;
import com.recoup.util.TimelineFormatter;
import java.time.Instant;

/** Helper class to build and format human-readable AuditEvents for the RecoveryOrchestrator. */
public class AuditTrailBuilder {

    public static AuditEvent buildFailureRecordedEvent(PaymentFailure failure) {
        return new AuditEvent(
                failure.failedAt(),
                AuditEventType.FAILURE_RECORDED,
                String.format("Payment failure imported [%s]: %s via %s (Context: %s)",
                        failure.paymentId(),
                        TimelineFormatter.formatRupees(failure.amountPaise()),
                        failure.paymentMethod(),
                        failure.context())
        );
    }

    public static AuditEvent buildDiagnosedEvent(PaymentFailure failure, FailureDiagnosis diagnosis) {
        return new AuditEvent(
                failure.failedAt(),
                AuditEventType.DIAGNOSED,
                String.format("Diagnosed as %s (Source: %s, Confidence: %s) — Evidence: %s",
                        diagnosis.failureType(),
                        diagnosis.source(),
                        diagnosis.confidence(),
                        diagnosis.evidence())
        );
    }

    public static AuditEvent buildPlanCreatedEvent(PaymentFailure failure, RecoveryPlan plan) {
        return new AuditEvent(
                failure.failedAt(),
                AuditEventType.PLAN_CREATED,
                String.format("Recovery plan created with %d scheduled action(s). Rule: %s",
                        plan.plannedActions().size(),
                        plan.stoppingRationale())
        );
    }

    public static AuditEvent buildActionExecutedEvent(PaymentFailure failure, ActionExecution exec) {
        String resultStr = exec.result() == ActionResult.SUCCEEDED ? "SUCCEEDED" :
                           (exec.result() == ActionResult.FAILED ? "FAILED" : "SKIPPED");
        return new AuditEvent(
                exec.executedAt(),
                AuditEventType.ACTION_EXECUTED,
                String.format("%s [%s] %s (Cost: %s) — %s",
                        exec.plannedAction().actionType(),
                        TimelineFormatter.formatDayOffset(failure.failedAt(), exec.executedAt()),
                        resultStr,
                        TimelineFormatter.formatRupees(exec.costPaise()),
                        exec.outcomeNote())
        );
    }

    public static AuditEvent buildTerminalActionExecutedEvent(ActionExecution exec) {
        return new AuditEvent(
                exec.executedAt(),
                AuditEventType.ACTION_EXECUTED,
                String.format("%s: %s (Cost: %s)",
                        exec.plannedAction().actionType(),
                        exec.outcomeNote(),
                        TimelineFormatter.formatRupees(exec.costPaise()))
        );
    }

    public static AuditEvent buildPaymentRecoveredEvent(PaymentFailure failure, ActionExecution exec) {
        return new AuditEvent(
                exec.executedAt(),
                AuditEventType.PAYMENT_RECOVERED,
                String.format("Payment successfully recovered (%s). Pending scheduled follow-ups cancelled.",
                        TimelineFormatter.formatRupees(failure.amountPaise()))
        );
    }

    public static AuditEvent buildRecoveryStoppedEvent(Instant time, String reason) {
        return new AuditEvent(
                time,
                AuditEventType.RECOVERY_STOPPED,
                reason
        );
    }
}
