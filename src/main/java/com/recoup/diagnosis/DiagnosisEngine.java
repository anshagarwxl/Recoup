package com.recoup.diagnosis;

import com.recoup.domain.DiagnosisSource;
import com.recoup.domain.FailureDiagnosis;
import com.recoup.domain.FailureType;
import com.recoup.domain.PaymentFailure;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Orchestrates payment failure diagnoses using deterministic lookup rules and an LLM fallback. */
@Service
public class DiagnosisEngine {

    private static final Map<String, FailureType> LOOKUP_TABLE = new HashMap<>();

    static {
        // Insufficient funds mappings
        LOOKUP_TABLE.put("INSUFFICIENT_FUNDS", FailureType.INSUFFICIENT_FUNDS);
        LOOKUP_TABLE.put("INS_FUNDS", FailureType.INSUFFICIENT_FUNDS);

        // UPI mandate mappings
        LOOKUP_TABLE.put("UPI_MANDATE_INACTIVE", FailureType.UPI_MANDATE_INACTIVE);

        // UPI collect expired mappings
        LOOKUP_TABLE.put("UPI_COLLECT_EXPIRED", FailureType.UPI_COLLECT_EXPIRED);

        // Bank technical outage mappings
        LOOKUP_TABLE.put("BANK_TECHNICAL_ERROR", FailureType.BANK_TECHNICAL_ERROR);
        LOOKUP_TABLE.put("BAD_GATEWAY", FailureType.BANK_TECHNICAL_ERROR);
        LOOKUP_TABLE.put("INTERNAL_SERVER_ERROR", FailureType.BANK_TECHNICAL_ERROR);

        // Card declines
        LOOKUP_TABLE.put("CARD_DECLINED", FailureType.CARD_DECLINED);
        LOOKUP_TABLE.put("CARD_EXPIRED", FailureType.CARD_DECLINED);

        // Authentication failures
        LOOKUP_TABLE.put("AUTHENTICATION_FAILED", FailureType.AUTHENTICATION_FAILED);
        LOOKUP_TABLE.put("UPI_PIN_INCORRECT", FailureType.AUTHENTICATION_FAILED);

        // Payment timeouts
        LOOKUP_TABLE.put("PAYMENT_TIMEOUT", FailureType.PAYMENT_TIMEOUT);

        // Hard decline / fraud flags (compliance terminal — zero recovery permitted)
        LOOKUP_TABLE.put("FRAUD_FLAGGED", FailureType.HARD_DECLINE);
        LOOKUP_TABLE.put("STOLEN_CARD", FailureType.HARD_DECLINE);
        LOOKUP_TABLE.put("DO_NOT_HONOR", FailureType.HARD_DECLINE);
    }

    private final GeminiClient geminiClient;

    public DiagnosisEngine(GeminiClient geminiClient) {
        this.geminiClient = Objects.requireNonNull(geminiClient, "geminiClient must not be null");
    }

    /** Resets the Gemini per-batch quota. Call at the start of each processBatch() run. */
    public void resetGeminiQuota() {
        geminiClient.resetForNewBatch();
    }

    /**
     * Categorizes a payment failure.
     * Looks up clean code rules first. If code is missing/unknown, delegates to Gemini Flash.
     */
    public FailureDiagnosis diagnose(PaymentFailure failure) {
        Objects.requireNonNull(failure, "failure must not be null");

        String code = failure.gatewayFailureCode();
        if (code != null && !code.isBlank()) {
            FailureType type = LOOKUP_TABLE.get(code.trim().toUpperCase());
            if (type != null) {
                return new FailureDiagnosis(
                        type,
                        BigDecimal.ONE, // 100% confidence for deterministic gateway code mapping
                        "Deterministic gateway code lookup match: " + code,
                        DiagnosisSource.GATEWAY_CODE
                );
            }
        }

        // Delegate ambiguous or free-text failures to Gemini Flash
        return geminiClient.classifyFreeText(
                failure.gatewayFailureReason(),
                failure.context(),
                failure.paymentMethod()
        );
    }
}
