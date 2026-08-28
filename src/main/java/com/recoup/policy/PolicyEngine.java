package com.recoup.policy;

import com.recoup.domain.FailureDiagnosis;
import com.recoup.domain.FailureType;
import com.recoup.domain.PaymentContext;
import com.recoup.domain.PaymentFailure;
import com.recoup.domain.PlannedAction;
import com.recoup.domain.RecoveryActionType;
import com.recoup.domain.RecoveryPlan;
import com.recoup.domain.RecoveryStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Deterministic policy engine mapping diagnosed payment failures to recovery actions. */
@Service
public class PolicyEngine {

    private static final long HIGH_VALUE_THRESHOLD_PAISE = 1_000_000L; // ₹10,000
    private static final long ESCALATION_COST_PAISE = 5_000L; // ₹50

    /**
     * Evaluates a diagnosed failed payment and builds its recovery action sequence.
     *
     * @param failure the raw payment failure details.
     * @param diagnosis the failure diagnosis classification.
     * @return the mapped RecoveryPlan.
     */
    public RecoveryPlan createPlan(PaymentFailure failure, FailureDiagnosis diagnosis) {
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(diagnosis, "diagnosis must not be null");

        FailureType failureType = diagnosis.failureType();
        List<PlannedAction> plannedActions = new ArrayList<>();
        RecoveryStatus status = RecoveryStatus.IN_PROGRESS;
        String stoppingRationale = "Stop after executing all planned actions or if payment succeeds.";

        switch (failureType) {
            case HARD_DECLINE:
                // Terminal compliance failure. Schedule a STOP_RECOVERY action to satisfy the non-empty actions constraint.
                plannedActions.add(new PlannedAction(
                        RecoveryActionType.STOP_RECOVERY,
                        failure.failedAt(),
                        "Stop recovery immediately due to terminal compliance/decline flag",
                        0L
                ));
                status = RecoveryStatus.STOPPED;
                stoppingRationale = "Terminal fraud or decline flag; no attempts permitted.";
                break;

            case INSUFFICIENT_FUNDS:
                if (failure.context() == PaymentContext.SUBSCRIPTION_RENEWAL) {
                    plannedActions.add(new PlannedAction(
                            RecoveryActionType.SEND_UPI_REMINDER,
                            failure.failedAt().plusSeconds(3600), // +1 hour
                            "Nudge user to top up balance for subscription",
                            100L // ₹1
                    ));
                    plannedActions.add(new PlannedAction(
                            RecoveryActionType.RETRY_PAYMENT,
                            failure.failedAt().plusSeconds(86400), // +24 hours
                            "Auto-retry debit after top-up window",
                            500L // ₹5
                    ));
                    stoppingRationale = "Stop after 1 retry attempt.";
                } else if (failure.context() == PaymentContext.ONE_TIME_CHECKOUT) {
                    plannedActions.add(new PlannedAction(
                            RecoveryActionType.SEND_PAYMENT_LINK,
                            failure.failedAt().plusSeconds(900), // +15 minutes
                            "Send backup payment link since checkout failed",
                            200L // ₹2
                    ));
                    stoppingRationale = "Stop after sending 1 payment link.";
                } else {
                    // Fallback for B2B or other contexts
                    plannedActions.add(new PlannedAction(
                            RecoveryActionType.SEND_PAYMENT_LINK,
                            failure.failedAt().plusSeconds(3600), // +1 hour
                            "Send payment link for outstanding balance",
                            200L // ₹2
                    ));
                    stoppingRationale = "Stop after sending 1 payment link.";
                }
                break;

            case UPI_MANDATE_INACTIVE:
                plannedActions.add(new PlannedAction(
                        RecoveryActionType.SEND_PAYMENT_LINK,
                        failure.failedAt().plusSeconds(3600), // +1 hour
                        "Mandate inactive; send link to establish new mandate or pay invoice",
                        200L // ₹2
                ));
                stoppingRationale = "Stop after link nudge.";
                break;

            case AUTHENTICATION_FAILED:
            case CARD_DECLINED:
                plannedActions.add(new PlannedAction(
                        RecoveryActionType.SEND_PAYMENT_LINK,
                        failure.failedAt().plusSeconds(1800), // +30 minutes
                        "Authentication failed or card declined; send secure payment link",
                        200L // ₹2
                ));
                stoppingRationale = "Stop after sending 1 recovery link.";
                break;

            case BANK_TECHNICAL_ERROR:
            case PAYMENT_TIMEOUT:
                plannedActions.add(new PlannedAction(
                        RecoveryActionType.RETRY_PAYMENT,
                        failure.failedAt().plusSeconds(3600), // +1 hour
                        "Temporary bank outage; retry payment after short window",
                        500L // ₹5
                ));
                plannedActions.add(new PlannedAction(
                        RecoveryActionType.RETRY_PAYMENT,
                        failure.failedAt().plusSeconds(21600), // +6 hours
                        "Outage persistent; final bank retry attempt",
                        500L // ₹5
                ));
                stoppingRationale = "Stop after 2 automated retries.";
                break;

            case UNKNOWN:
            default:
                plannedActions.add(new PlannedAction(
                        RecoveryActionType.ESCALATE_TO_ACCOUNT_MANAGER,
                        failure.failedAt(), // +0 hours (immediate)
                        "Manual operations review required due to ambiguous failure reason",
                        ESCALATION_COST_PAISE // ₹50
                ));
                status = RecoveryStatus.STOPPED;
                stoppingRationale = "Immediate stop; case escalated for manual review.";
                break;
        }

        // Apply Cross-cutting High-Value Escalation Overlay
        // Triggers if amount > ₹10,000, not a hard decline, and not already escalated (preventing duplicates on UNKNOWN)
        if (failure.amountPaise() > HIGH_VALUE_THRESHOLD_PAISE
                && failureType != FailureType.HARD_DECLINE
                && !containsEscalation(plannedActions)) {
            
            plannedActions.add(new PlannedAction(
                    RecoveryActionType.ESCALATE_TO_ACCOUNT_MANAGER,
                    failure.failedAt().plusSeconds(172800), // +48 hours
                    "High-value transaction recovery escalation overlay",
                    ESCALATION_COST_PAISE // ₹50
            ));
        }

        return new RecoveryPlan(status, plannedActions, stoppingRationale);
    }

    private boolean containsEscalation(List<PlannedAction> actions) {
        return actions.stream().anyMatch(a -> a.actionType() == RecoveryActionType.ESCALATE_TO_ACCOUNT_MANAGER);
    }
}
