package com.recoup.orchestrator;

import com.recoup.diagnosis.DiagnosisEngine;
import com.recoup.domain.ActionExecution;
import com.recoup.domain.ActionResult;
import com.recoup.domain.AuditEvent;
import com.recoup.domain.AuditEventType;
import com.recoup.domain.DiagnosisSource;
import com.recoup.domain.FailureDiagnosis;
import com.recoup.domain.FailureType;
import com.recoup.domain.PaymentFailure;
import com.recoup.domain.PaymentMethod;
import com.recoup.domain.PlannedAction;
import com.recoup.domain.RecoveryActionType;
import com.recoup.domain.RecoveryCase;
import com.recoup.domain.RecoveryPlan;
import com.recoup.domain.RecoveryStatus;
import com.recoup.executor.RecoveryExecutor;
import com.recoup.policy.PolicyEngine;
import com.recoup.util.TimelineFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the end-to-end recovery lifecycle for failed payments:
 * imports failures, triggers diagnosis, maps deterministic policy, simulates executions,
 * applies early-stopping rules, and aggregates net recovery metrics.
 */
@Service
public class RecoveryOrchestrator {

    private final DiagnosisEngine diagnosisEngine;
    private final PolicyEngine policyEngine;
    private final RecoveryExecutor recoveryExecutor;

    public RecoveryOrchestrator(
            DiagnosisEngine diagnosisEngine,
            PolicyEngine policyEngine,
            RecoveryExecutor recoveryExecutor) {
        this.diagnosisEngine = Objects.requireNonNull(diagnosisEngine, "diagnosisEngine must not be null");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine must not be null");
        this.recoveryExecutor = Objects.requireNonNull(recoveryExecutor, "recoveryExecutor must not be null");
    }

    /**
     * Processes a batch of payment failures through the complete recovery pipeline.
     */
    public List<RecoveryCase> processBatch(List<PaymentFailure> failures) {
        Objects.requireNonNull(failures, "failures must not be null");
        List<RecoveryCase> cases = new ArrayList<>();
        for (PaymentFailure failure : failures) {
            cases.add(processCase(failure));
        }
        return cases;
    }

    /**
     * Processes a single payment failure through diagnosis, policy scheduling, and simulated execution.
     */
    public RecoveryCase processCase(PaymentFailure failure) {
        Objects.requireNonNull(failure, "failure must not be null");

        List<AuditEvent> auditTrail = new ArrayList<>();

        // 1. Log failure ingestion
        auditTrail.add(new AuditEvent(
                failure.failedAt(),
                AuditEventType.FAILURE_RECORDED,
                String.format("Payment failure imported [%s]: %s via %s (Context: %s)",
                        failure.paymentId(),
                        TimelineFormatter.formatRupees(failure.amountPaise()),
                        failure.paymentMethod(),
                        failure.context())
        ));

        // 2. Perform diagnosis
        FailureDiagnosis diagnosis = diagnosisEngine.diagnose(failure);
        auditTrail.add(new AuditEvent(
                failure.failedAt(),
                AuditEventType.DIAGNOSED,
                String.format("Diagnosed as %s (Source: %s, Confidence: %s) — Evidence: %s",
                        diagnosis.failureType(),
                        diagnosis.source(),
                        diagnosis.confidence(),
                        diagnosis.evidence())
        ));

        // 3. Create deterministic recovery plan
        RecoveryPlan initialPlan = policyEngine.createPlan(failure, diagnosis);
        auditTrail.add(new AuditEvent(
                failure.failedAt(),
                AuditEventType.PLAN_CREATED,
                String.format("Recovery plan created with %d scheduled action(s). Rule: %s",
                        initialPlan.plannedActions().size(),
                        initialPlan.stoppingRationale())
        ));

        List<ActionExecution> executions = new ArrayList<>();
        boolean paymentRecovered = false;
        RecoveryStatus finalStatus;

        if (initialPlan.status() == RecoveryStatus.STOPPED) {
            // Compliance stop or immediate escalation
            for (PlannedAction action : initialPlan.plannedActions()) {
                ActionExecution exec = recoveryExecutor.execute(action, diagnosis.failureType(), false);
                executions.add(exec);
                auditTrail.add(new AuditEvent(
                        exec.executedAt(),
                        AuditEventType.ACTION_EXECUTED,
                        String.format("%s: %s (Cost: %s)",
                                action.actionType(),
                                exec.outcomeNote(),
                                TimelineFormatter.formatRupees(exec.costPaise()))
                ));
            }
            finalStatus = RecoveryStatus.STOPPED;
            auditTrail.add(new AuditEvent(
                    failure.failedAt(),
                    AuditEventType.RECOVERY_STOPPED,
                    "Automated recovery halted by policy stopping rule."
            ));
        } else {
            // Sequential execution with Early-Stopping rule
            for (PlannedAction action : initialPlan.plannedActions()) {
                // If payment already succeeded in an earlier action, forceSkip remaining actions
                ActionExecution exec = recoveryExecutor.execute(action, diagnosis.failureType(), paymentRecovered);
                executions.add(exec);

                if (exec.result() == ActionResult.SUCCEEDED) {
                    paymentRecovered = true;
                    auditTrail.add(new AuditEvent(
                            exec.executedAt(),
                            AuditEventType.ACTION_EXECUTED,
                            String.format("%s [%s] SUCCEEDED (Cost: %s) — %s",
                                    action.actionType(),
                                    TimelineFormatter.formatDayOffset(failure.failedAt(), exec.executedAt()),
                                    TimelineFormatter.formatRupees(exec.costPaise()),
                                    exec.outcomeNote())
                    ));
                    auditTrail.add(new AuditEvent(
                            exec.executedAt(),
                            AuditEventType.PAYMENT_RECOVERED,
                            String.format("Payment successfully recovered (%s). Pending scheduled follow-ups cancelled.",
                                    TimelineFormatter.formatRupees(failure.amountPaise()))
                    ));
                } else if (exec.result() == ActionResult.FAILED) {
                    auditTrail.add(new AuditEvent(
                            exec.executedAt(),
                            AuditEventType.ACTION_EXECUTED,
                            String.format("%s [%s] FAILED (Cost: %s) — %s",
                                    action.actionType(),
                                    TimelineFormatter.formatDayOffset(failure.failedAt(), exec.executedAt()),
                                    TimelineFormatter.formatRupees(exec.costPaise()),
                                    exec.outcomeNote())
                    ));
                } else {
                    auditTrail.add(new AuditEvent(
                            exec.executedAt(),
                            AuditEventType.ACTION_EXECUTED,
                            String.format("%s [%s] SKIPPED (Cost: %s) — %s",
                                    action.actionType(),
                                    TimelineFormatter.formatDayOffset(failure.failedAt(), exec.executedAt()),
                                    TimelineFormatter.formatRupees(exec.costPaise()),
                                    exec.outcomeNote())
                    ));
                }
            }

            if (paymentRecovered) {
                finalStatus = RecoveryStatus.RECOVERED;
            } else {
                finalStatus = RecoveryStatus.UNRESOLVED;
                auditTrail.add(new AuditEvent(
                        executions.isEmpty() ? failure.failedAt() : executions.get(executions.size() - 1).executedAt(),
                        AuditEventType.RECOVERY_STOPPED,
                        "All scheduled recovery actions executed without resolution. Case marked UNRESOLVED."
                ));
            }
        }

        RecoveryPlan finalPlan = new RecoveryPlan(finalStatus, initialPlan.plannedActions(), initialPlan.stoppingRationale());
        return new RecoveryCase(failure, diagnosis, finalPlan, executions, auditTrail);
    }

    /**
     * Aggregates financial and operational metrics from a processed batch of cases.
     */
    public RecoveryMetrics calculateMetrics(List<RecoveryCase> cases) {
        Objects.requireNonNull(cases, "cases must not be null");

        long totalFailures = cases.size();
        long totalFailedAmount = 0L;
        long recoveredCount = 0L;
        long grossRecovered = 0L;
        long totalCost = 0L;
        long unresolvedCount = 0L;
        long stoppedCount = 0L;

        Map<FailureType, Long> byType = new EnumMap<>(FailureType.class);
        Map<PaymentMethod, Long> byMethod = new EnumMap<>(PaymentMethod.class);
        Map<DiagnosisSource, Long> bySource = new EnumMap<>(DiagnosisSource.class);

        for (RecoveryCase rCase : cases) {
            PaymentFailure f = rCase.paymentFailure();
            totalFailedAmount += f.amountPaise();

            // Track breakdowns
            byType.merge(rCase.diagnosis().failureType(), 1L, Long::sum);
            byMethod.merge(f.paymentMethod(), 1L, Long::sum);
            bySource.merge(rCase.diagnosis().source(), 1L, Long::sum);

            // Accumulate actual action costs
            for (ActionExecution exec : rCase.executions()) {
                totalCost += exec.costPaise();
            }

            // Track status buckets
            RecoveryStatus status = rCase.plan().status();
            if (status == RecoveryStatus.RECOVERED) {
                recoveredCount++;
                grossRecovered += f.amountPaise();
            } else if (status == RecoveryStatus.UNRESOLVED) {
                unresolvedCount++;
            } else if (status == RecoveryStatus.STOPPED) {
                stoppedCount++;
            }
        }

        long netRecovered = grossRecovered - totalCost;
        double recoveryRate = totalFailures > 0 ? ((double) recoveredCount / totalFailures) * 100.0 : 0.0;

        return new RecoveryMetrics(
                totalFailures,
                totalFailedAmount,
                recoveredCount,
                grossRecovered,
                totalCost,
                netRecovered,
                recoveryRate,
                unresolvedCount,
                stoppedCount,
                byType,
                byMethod,
                bySource
        );
    }
}
