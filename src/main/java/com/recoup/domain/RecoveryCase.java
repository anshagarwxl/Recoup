package com.recoup.domain;

import java.util.List;
import java.util.Objects;

/** Complete, view-ready recovery record for one failed payment. */
public record RecoveryCase(
        PaymentFailure paymentFailure,
        FailureDiagnosis diagnosis,
        RecoveryPlan plan,
        List<ActionExecution> executions,
        List<AuditEvent> auditTrail,
        RecoveryGroup group) {

    public RecoveryCase {
        paymentFailure = Objects.requireNonNull(paymentFailure, "paymentFailure must not be null");
        diagnosis = Objects.requireNonNull(diagnosis, "diagnosis must not be null");
        plan = Objects.requireNonNull(plan, "plan must not be null");
        executions = List.copyOf(Objects.requireNonNull(executions, "executions must not be null"));
        auditTrail = List.copyOf(Objects.requireNonNull(auditTrail, "auditTrail must not be null"));
        group = Objects.requireNonNullElse(group, RecoveryGroup.TREATMENT);
        if (auditTrail.isEmpty()) {
            throw new IllegalArgumentException("auditTrail must not be empty");
        }
    }
}

