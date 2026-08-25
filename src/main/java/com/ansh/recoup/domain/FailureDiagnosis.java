package com.ansh.recoup.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record FailureDiagnosis(
        FailureType failureType,
        BigDecimal confidence,
        String evidence,
        DiagnosisSource source) {

    public FailureDiagnosis {
        failureType = Objects.requireNonNull(failureType, "failureType must not be null");
        confidence = Objects.requireNonNull(confidence, "confidence must not be null");
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("evidence must not be blank");
        }
        evidence = evidence.trim();
        source = Objects.requireNonNull(source, "source must not be null");
    }
}
