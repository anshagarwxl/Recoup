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
import com.recoup.domain.RecoveryGroup;
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
 *
 * <p>Every fifth case (index % 5 == 0) is assigned to a 20% CONTROL holdout group.
 * Control cases receive no intervention and are used to compute an incremental recovery lift
 * metric: how much more revenue the active policy engine recovered compared to doing nothing.
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
     * Every fifth case is deterministically assigned to the control group (20% holdout).
     */
    public List<RecoveryCase> processBatch(List<PaymentFailure> failures) {
        Objects.requireNonNull(failures, "failures must not be null");
        // Reset Gemini per-batch quota so each run (startup + Re-run Simulation) gets its own fresh allowance.
        diagnosisEngine.resetGeminiQuota();
        List<RecoveryCase> cases = new ArrayList<>();
        for (int i = 0; i < failures.size(); i++) {
            RecoveryGroup group = (i % 5 == 0) ? RecoveryGroup.CONTROL : RecoveryGroup.TREATMENT;
            cases.add(processCaseWithGroup(failures.get(i), group));
        }
        return cases;
    }

    /**
     * Processes a single payment failure as a TREATMENT case through the full pipeline.
     * Used directly in tests and for single-case inspection.
     */
    public RecoveryCase processCase(PaymentFailure failure) {
        return processCaseWithGroup(failure, RecoveryGroup.TREATMENT);
    }

    /**
     * Processes a single payment failure with an explicit group assignment.
     * CONTROL cases are diagnosed (so they contribute to source stats) but receive no intervention.
     * TREATMENT cases go through the full policy + execution pipeline.
     */
    private RecoveryCase processCaseWithGroup(PaymentFailure failure, RecoveryGroup group) {
        Objects.requireNonNull(failure, "failure must not be null");

        List<AuditEvent> auditTrail = new ArrayList<>();

        // 1. Log failure ingestion
        auditTrail.add(AuditTrailBuilder.buildFailureRecordedEvent(failure));

        // 2. Perform diagnosis (runs for ALL cases, including control, to maintain accurate source stats)
        FailureDiagnosis diagnosis = diagnosisEngine.diagnose(failure);
        auditTrail.add(AuditTrailBuilder.buildDiagnosedEvent(failure, diagnosis));

        // 3a. CONTROL path: no intervention, hold out for baseline measurement
        if (group == RecoveryGroup.CONTROL) {
            auditTrail.add(new AuditEvent(
                    failure.failedAt(),
                    AuditEventType.CONTROL_HOLD,
                    "CONTROL GROUP: This case is held out for baseline measurement. "
                    + "No automated recovery actions will be applied. "
                    + "Natural recovery (if any) will be observed and reported as the incremental baseline."
            ));
            PlannedAction holdAction = new PlannedAction(
                    RecoveryActionType.STOP_RECOVERY,
                    failure.failedAt(),
                    "CONTROL GROUP — No intervention. Held out for incremental recovery baseline.",
                    0L
            );
            RecoveryPlan controlPlan = new RecoveryPlan(
                    RecoveryStatus.STOPPED,
                    List.of(holdAction),
                    "Control group holdout. No automated recovery actions applied. "
                    + "Case observed for natural recovery baseline."
            );
            return new RecoveryCase(failure, diagnosis, controlPlan, List.of(), auditTrail, RecoveryGroup.CONTROL);
        }

        // 3b. TREATMENT path: full deterministic policy + execution pipeline
        RecoveryPlan initialPlan = policyEngine.createPlan(failure, diagnosis);
        auditTrail.add(AuditTrailBuilder.buildPlanCreatedEvent(failure, initialPlan));

        List<ActionExecution> executions = new ArrayList<>();
        boolean paymentRecovered = false;
        RecoveryStatus finalStatus;

        if (initialPlan.status() == RecoveryStatus.STOPPED) {
            // Compliance stop or immediate escalation
            for (PlannedAction action : initialPlan.plannedActions()) {
                ActionExecution exec = recoveryExecutor.execute(action, diagnosis.failureType(), false);
                executions.add(exec);
                auditTrail.add(AuditTrailBuilder.buildTerminalActionExecutedEvent(exec));
            }
            finalStatus = RecoveryStatus.STOPPED;
            auditTrail.add(AuditTrailBuilder.buildRecoveryStoppedEvent(
                    failure.failedAt(),
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
                    auditTrail.add(AuditTrailBuilder.buildActionExecutedEvent(failure, exec));
                    auditTrail.add(AuditTrailBuilder.buildPaymentRecoveredEvent(failure, exec));
                } else if (exec.result() == ActionResult.FAILED) {
                    auditTrail.add(AuditTrailBuilder.buildActionExecutedEvent(failure, exec));
                } else {
                    auditTrail.add(AuditTrailBuilder.buildActionExecutedEvent(failure, exec));
                }
            }

            if (paymentRecovered) {
                finalStatus = RecoveryStatus.RECOVERED;
            } else {
                finalStatus = RecoveryStatus.UNRESOLVED;
                auditTrail.add(AuditTrailBuilder.buildRecoveryStoppedEvent(
                        executions.isEmpty() ? failure.failedAt() : executions.get(executions.size() - 1).executedAt(),
                        "All scheduled recovery actions executed without resolution. Case marked UNRESOLVED."
                ));
            }
        }

        RecoveryPlan finalPlan = new RecoveryPlan(finalStatus, initialPlan.plannedActions(), initialPlan.stoppingRationale());
        return new RecoveryCase(failure, diagnosis, finalPlan, executions, auditTrail, RecoveryGroup.TREATMENT);
    }

    /**
     * Aggregates financial and operational metrics from a processed batch of cases.
     * Control cases are separated from treatment cases to compute incremental recovery lift.
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

        long controlGroupCount = 0L;
        long controlNaturalRecoveredPaise = 0L;

        Map<FailureType, Long> byType = new EnumMap<>(FailureType.class);
        Map<PaymentMethod, Long> byMethod = new EnumMap<>(PaymentMethod.class);
        Map<DiagnosisSource, Long> bySource = new EnumMap<>(DiagnosisSource.class);

        for (RecoveryCase rCase : cases) {
            PaymentFailure f = rCase.paymentFailure();
            totalFailedAmount += f.amountPaise();

            // Track breakdowns for ALL cases including control (diagnosis ran on all of them)
            byType.merge(rCase.diagnosis().failureType(), 1L, Long::sum);
            byMethod.merge(f.paymentMethod(), 1L, Long::sum);
            bySource.merge(rCase.diagnosis().source(), 1L, Long::sum);

            if (rCase.group() == RecoveryGroup.CONTROL) {
                // Control cases: count as STOPPED (no intervention), track natural recovery separately.
                // Natural recovery is determined deterministically: ~1 in 6 control cases recover on their own,
                // simulating customers who retry independently or transient errors that self-resolve.
                controlGroupCount++;
                stoppedCount++;
                if (Math.abs(f.paymentId().hashCode()) % 6 == 0) {
                    controlNaturalRecoveredPaise += f.amountPaise();
                }
                continue;
            }

            // TREATMENT cases: accumulate actual action costs and status buckets
            for (ActionExecution exec : rCase.executions()) {
                totalCost += exec.costPaise();
            }

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

        // Incremental lift = treatment net recovered - control natural recovery baseline.
        // Measures how much additional revenue the active policy engine generated
        // compared to a world with no intervention.
        long incrementalNetRecoveredPaise = netRecovered - controlNaturalRecoveredPaise;

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
                bySource,
                controlGroupCount,
                controlNaturalRecoveredPaise,
                incrementalNetRecoveredPaise
        );
    }
}
