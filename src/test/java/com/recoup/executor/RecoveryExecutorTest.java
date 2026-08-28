package com.recoup.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.recoup.domain.ActionExecution;
import com.recoup.domain.ActionResult;
import com.recoup.domain.FailureType;
import com.recoup.domain.PlannedAction;
import com.recoup.domain.RecoveryActionType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecoveryExecutorTest {

    private static final Instant TIME = Instant.parse("2026-08-25T12:00:00Z");

    @Test
    void forceSkipYieldsSkippedOutcomeAndZeroCost() {
        RecoveryExecutor executor = new RecoveryExecutor(42L);
        PlannedAction action = new PlannedAction(
                RecoveryActionType.SEND_PAYMENT_LINK, TIME, "Send invoice recovery link", 200L
        );

        ActionExecution execution = executor.execute(action, FailureType.INSUFFICIENT_FUNDS, true);

        assertNotNull(execution);
        assertEquals(ActionResult.SKIPPED, execution.result());
        assertEquals(0L, execution.costPaise());
        assertEquals("Skipped: Recovery already succeeded", execution.outcomeNote());
    }

    @Test
    void stopRecoveryYieldsSkippedOutcomeAndZeroCost() {
        RecoveryExecutor executor = new RecoveryExecutor(42L);
        PlannedAction action = new PlannedAction(
                RecoveryActionType.STOP_RECOVERY, TIME, "Compliance stop", 0L
        );

        ActionExecution execution = executor.execute(action, FailureType.HARD_DECLINE, false);

        assertNotNull(execution);
        assertEquals(ActionResult.SKIPPED, execution.result());
        assertEquals(0L, execution.costPaise());
        assertEquals("Stop action processed; recovery halted", execution.outcomeNote());
    }

    @Test
    void retryPaymentChargesCostForAttempts() {
        // Build executor with fixed seed so rolls are predictable
        RecoveryExecutor executor1 = new RecoveryExecutor(12345L);
        RecoveryExecutor executor2 = new RecoveryExecutor(12345L);

        PlannedAction action = new PlannedAction(
                RecoveryActionType.RETRY_PAYMENT, TIME, "Debit retry attempt", 500L
        );

        // Run both executors to verify deterministic success/failure outcomes
        ActionExecution exec1 = executor1.execute(action, FailureType.INSUFFICIENT_FUNDS, false);
        ActionExecution exec2 = executor2.execute(action, FailureType.INSUFFICIENT_FUNDS, false);

        // Both should yield the exact same result given the same seed
        assertEquals(exec1.result(), exec2.result());
        assertEquals(500L, exec1.costPaise()); // Cost charged on attempt
        assertEquals(500L, exec2.costPaise());
    }

    @Test
    void verifiesTargetCostAccrualForDifferentInterventions() {
        RecoveryExecutor executor = new RecoveryExecutor(999L);
        
        PlannedAction link = new PlannedAction(RecoveryActionType.SEND_PAYMENT_LINK, TIME, "Link", 200L);
        PlannedAction escalation = new PlannedAction(RecoveryActionType.ESCALATE_TO_ACCOUNT_MANAGER, TIME, "Escalate", 5000L);

        ActionExecution linkResult = executor.execute(link, FailureType.CARD_DECLINED, false);
        ActionExecution escalationResult = executor.execute(escalation, FailureType.UNKNOWN, false);

        // Ensure we always charge the planned cost on execution
        assertEquals(200L, linkResult.costPaise());
        assertEquals(5000L, escalationResult.costPaise());
    }
}
