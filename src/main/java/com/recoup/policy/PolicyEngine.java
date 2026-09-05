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
                stoppingRationale = "Terminal fraud or compliance flag. Per RBI Master Direction on Digital Payments, retries are prohibited on fraud-flagged instruments. No further automated action taken.";
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
                    stoppingRationale = "Capped at 1 automated retry. Per NPCI UPI e-mandate circular (Oct 2021), excessive automated retries on failed debit mandates are prohibited. Retry scheduled at T+24h after a customer top-up window.";
                } else if (failure.context() == PaymentContext.ONE_TIME_CHECKOUT) {
                    plannedActions.add(new PlannedAction(
                            RecoveryActionType.SEND_PAYMENT_LINK,
                            failure.failedAt().plusSeconds(900), // +15 minutes
                            "Send backup payment link since checkout failed",
                            200L // ₹2
                    ));
                    stoppingRationale = "One-time checkout context. A single recovery payment link is issued at T+15min. No automated debit retries are permitted without fresh customer authorization.";
                } else {
                    // Fallback for B2B or other contexts
                    plannedActions.add(new PlannedAction(
                            RecoveryActionType.SEND_PAYMENT_LINK,
                            failure.failedAt().plusSeconds(3600), // +1 hour
                            "Send payment link for outstanding balance",
                            200L // ₹2
                    ));
                    stoppingRationale = "Non-subscription context. A recovery payment link is issued at T+1h. No automated debit retries without fresh customer action.";
                }
                break;

            case UPI_MANDATE_INACTIVE:
            case UPI_COLLECT_EXPIRED:
                plannedActions.add(new PlannedAction(
                        RecoveryActionType.SEND_PAYMENT_LINK,
                        failure.failedAt().plusSeconds(3600), // +1 hour
                        "UPI mandate inactive or collect expired; send link to retry or re-authorize",
                        200L // ₹2
                ));
                stoppingRationale = "UPI mandate is inactive or collect request has expired. Per NPCI UPI mandate framework, a fresh mandate authorization is required before any debit can be initiated. Recovery link sent for re-authorization.";
                break;

            case AUTHENTICATION_FAILED:
            case CARD_DECLINED:
                plannedActions.add(new PlannedAction(
                        RecoveryActionType.SEND_PAYMENT_LINK,
                        failure.failedAt().plusSeconds(1800), // +30 minutes
                        "Authentication failed or card declined; send secure payment link",
                        200L // ₹2
                ));
                stoppingRationale = "Authentication failure or card decline. Repeated automated retries on authentication failures are not permitted per PCI-DSS guidelines. A secure payment link is issued for a customer-initiated re-attempt.";
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
                stoppingRationale = "Transient bank-side or network error. Capped at 2 automated retries (T+1h, T+6h) with mandatory spacing. Per NPCI operational guidelines, retries beyond this window require manual review.";
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
                stoppingRationale = "Ambiguous failure reason — AI classification returned UNKNOWN or MOCK_FALLBACK. Automated action suspended. Case escalated to operations team for manual review per internal compliance policy.";
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
