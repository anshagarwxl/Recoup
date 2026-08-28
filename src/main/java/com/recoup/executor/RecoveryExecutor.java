package com.recoup.executor;

import com.recoup.domain.ActionExecution;
import com.recoup.domain.ActionResult;
import com.recoup.domain.FailureType;
import com.recoup.domain.PlannedAction;
import com.recoup.domain.RecoveryActionType;
import java.util.Objects;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Simulated recovery action executor. Evaluates outcome resolutions and applies cost rules. */
@Component
public class RecoveryExecutor {

    private final Random random;

    public RecoveryExecutor(@Value("${RECOVERY_EXECUTOR_SEED:42}") long seed) {
        this.random = new Random(seed);
    }

    /**
     * Executes a planned action, rolling for outcome success based on targeted probabilities.
     *
     * @param action the planned action to execute.
     * @param failureType the failure type of the transaction (determines retry weights).
     * @param forceSkip if true, forces the action to be skipped immediately (e.g., if case is already recovered).
     * @return the execution result.
     */
    public ActionExecution execute(PlannedAction action, FailureType failureType, boolean forceSkip) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(failureType, "failureType must not be null");

        // Rule: cost = 0 only when result is SKIPPED
        if (forceSkip) {
            return new ActionExecution(
                    action,
                    ActionResult.SKIPPED,
                    action.scheduledFor(),
                    0L, // ₹0 cost for skipped
                    "Skipped: Recovery already succeeded"
            );
        }

        RecoveryActionType actionType = action.actionType();

        // STOP_RECOVERY is terminal compliance, always skipped with 0 cost
        if (actionType == RecoveryActionType.STOP_RECOVERY) {
            return new ActionExecution(
                    action,
                    ActionResult.SKIPPED,
                    action.scheduledFor(),
                    0L,
                    "Stop action processed; recovery halted"
            );
        }

        // Determine step probability of success
        double successRate;
        switch (actionType) {
            case RETRY_PAYMENT:
                if (failureType == FailureType.INSUFFICIENT_FUNDS) {
                    successRate = 0.15;
                } else if (failureType == FailureType.BANK_TECHNICAL_ERROR || failureType == FailureType.PAYMENT_TIMEOUT) {
                    successRate = 0.50;
                } else {
                    successRate = 0.20;
                }
                break;
            case SEND_UPI_REMINDER:
                successRate = 0.25;
                break;
            case SEND_PAYMENT_LINK:
                successRate = 0.30;
                break;
            case ESCALATE_TO_ACCOUNT_MANAGER:
                successRate = 0.40;
                break;
            default:
                successRate = 0.0;
                break;
        }

        // Roll for success using seeded Random
        double roll = random.nextDouble();
        boolean succeeded = roll < successRate;

        ActionResult result = succeeded ? ActionResult.SUCCEEDED : ActionResult.FAILED;
        long actualCost = action.costPaise(); // Rule: cost = plannedCost when SUCCEEDED or FAILED

        String note = String.format(
                "Execution %s (Roll: %.2f vs Rate: %.2f)",
                result.name(), roll, successRate
        );

        return new ActionExecution(
                action,
                result,
                action.scheduledFor(),
                actualCost,
                note
        );
    }
}
