package com.recoup.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoup.diagnosis.DiagnosisEngine;
import com.recoup.diagnosis.GeminiClient;
import com.recoup.domain.ActionResult;
import com.recoup.domain.AuditEventType;
import com.recoup.domain.PaymentContext;
import com.recoup.domain.PaymentFailure;
import com.recoup.domain.PaymentMethod;
import com.recoup.domain.RecoveryCase;
import com.recoup.domain.RecoveryStatus;
import com.recoup.executor.RecoveryExecutor;
import com.recoup.generator.SyntheticDataGenerator;
import com.recoup.policy.PolicyEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecoveryOrchestratorTest {

    private RecoveryOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        GeminiClient geminiClient = new GeminiClient(new ObjectMapper());
        DiagnosisEngine diagnosisEngine = new DiagnosisEngine(geminiClient);
        PolicyEngine policyEngine = new PolicyEngine();
        RecoveryExecutor recoveryExecutor = new RecoveryExecutor(42L);

        orchestrator = new RecoveryOrchestrator(diagnosisEngine, policyEngine, recoveryExecutor);
    }

    @Test
    void earlyStoppingCancelsSubsequentActionsOnSuccess() {
        // Mock a failure with multiple actions (e.g. Bank outage with 2 retries)
        Instant failureTime = Instant.parse("2026-08-25T10:00:00Z");
        PaymentFailure failure = new PaymentFailure(
                "pay_early_stop",
                "order_101",
                PaymentContext.SUBSCRIPTION_RENEWAL,
                149_900L, // ₹1,499
                PaymentMethod.UPI,
                failureTime,
                "BANK_TECHNICAL_ERROR",
                "Bank technical failure"
        );

        RecoveryCase rCase = orchestrator.processCase(failure);

        assertNotNull(rCase);
        assertFalse(rCase.executions().isEmpty());

        // Check that if an action succeeded, subsequent action was skipped
        boolean sawSuccess = false;
        for (int i = 0; i < rCase.executions().size(); i++) {
            var exec = rCase.executions().get(i);
            if (sawSuccess) {
                assertEquals(ActionResult.SKIPPED, exec.result(), "Action following a success must be SKIPPED");
                assertEquals(0L, exec.costPaise(), "Skipped action must have 0 cost");
            }
            if (exec.result() == ActionResult.SUCCEEDED) {
                sawSuccess = true;
            }
        }

        if (sawSuccess) {
            assertEquals(RecoveryStatus.RECOVERED, rCase.plan().status());
            assertTrue(rCase.auditTrail().stream().anyMatch(e -> e.type() == AuditEventType.PAYMENT_RECOVERED));
        } else {
            assertEquals(RecoveryStatus.UNRESOLVED, rCase.plan().status());
        }
    }

    @Test
    void hardDeclineTerminatesImmediatelyWithZeroCost() {
        Instant failureTime = Instant.parse("2026-08-25T10:00:00Z");
        PaymentFailure failure = new PaymentFailure(
                "pay_fraud",
                "order_fraud",
                PaymentContext.ONE_TIME_CHECKOUT,
                50_000L,
                PaymentMethod.CARD,
                failureTime,
                "FRAUD_FLAGGED",
                "Flagged as stolen card"
        );

        RecoveryCase rCase = orchestrator.processCase(failure);

        assertEquals(RecoveryStatus.STOPPED, rCase.plan().status());
        assertEquals(1, rCase.executions().size());
        assertEquals(0L, rCase.executions().get(0).costPaise());
        assertTrue(rCase.auditTrail().stream().anyMatch(e -> e.type() == AuditEventType.RECOVERY_STOPPED));
    }

    @Test
    void processBatchAggregatesNetRecoveryMetricsCorrectly() {
        SyntheticDataGenerator generator = new SyntheticDataGenerator();
        List<PaymentFailure> batch = generator.generateBatch(125, 42L);

        List<RecoveryCase> cases = orchestrator.processBatch(batch);
        assertEquals(125, cases.size());

        RecoveryMetrics metrics = orchestrator.calculateMetrics(cases);

        assertEquals(125, metrics.totalFailuresCount());
        assertTrue(metrics.totalFailedAmountPaise() > 0);
        assertTrue(metrics.recoveredCount() > 0);
        assertTrue(metrics.grossRecoveredPaise() > 0);
        assertTrue(metrics.totalCostPaise() > 0);

        // Verify Net = Gross - Total Cost
        assertEquals(metrics.grossRecoveredPaise() - metrics.totalCostPaise(), metrics.netRecoveredPaise());
        assertTrue(metrics.recoveryRatePercent() > 0.0);

        // Verify all 125 cases accounted for in terminal states
        assertEquals(125, metrics.recoveredCount() + metrics.unresolvedCount() + metrics.stoppedCount());

        // Verify breakdown maps populated
        assertFalse(metrics.failuresByType().isEmpty());
        assertFalse(metrics.failuresByMethod().isEmpty());
        assertFalse(metrics.diagnosesBySource().isEmpty());
    }
}
