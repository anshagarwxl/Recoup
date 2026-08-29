package com.recoup.orchestrator;

import com.recoup.domain.DiagnosisSource;
import com.recoup.domain.FailureType;
import com.recoup.domain.PaymentMethod;
import java.util.Map;

/** Container for aggregated batch recovery performance metrics and financial summaries. */
public record RecoveryMetrics(
        long totalFailuresCount,
        long totalFailedAmountPaise,
        long recoveredCount,
        long grossRecoveredPaise,
        long totalCostPaise,
        long netRecoveredPaise,
        double recoveryRatePercent,
        long unresolvedCount,
        long stoppedCount,
        Map<FailureType, Long> failuresByType,
        Map<PaymentMethod, Long> failuresByMethod,
        Map<DiagnosisSource, Long> diagnosesBySource) {

    public String formattedTotalFailedAmount() {
        return formatRupees(totalFailedAmountPaise);
    }

    public String formattedGrossRecovered() {
        return formatRupees(grossRecoveredPaise);
    }

    public String formattedTotalCost() {
        return formatRupees(totalCostPaise);
    }

    public String formattedNetRecovered() {
        return formatRupees(netRecoveredPaise);
    }

    public String formattedRecoveryRate() {
        return String.format("%.1f%%", recoveryRatePercent);
    }

    public String formattedRevenueRecoveryRate() {
        if (totalFailedAmountPaise == 0) return "0.0%";
        double rate = (grossRecoveredPaise * 100.0) / totalFailedAmountPaise;
        return String.format("%.1f%%", rate);
    }

    private static String formatRupees(long paise) {
        double rupees = paise / 100.0;
        return String.format("₹%,.2f", rupees);
    }
}
