package com.recoup.diagnosis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recoup.domain.DiagnosisSource;
import com.recoup.domain.FailureDiagnosis;
import com.recoup.domain.FailureType;
import com.recoup.domain.PaymentContext;
import com.recoup.domain.PaymentFailure;
import com.recoup.domain.PaymentMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiagnosisEngineTest {

    private GeminiClient mockGeminiClient;
    private DiagnosisEngine diagnosisEngine;

    @BeforeEach
    void setUp() {
        mockGeminiClient = mock(GeminiClient.class);
        diagnosisEngine = new DiagnosisEngine(mockGeminiClient);
    }

    @Test
    void diagnosesCleanCodeDeterministicLookup() {
        // Test mapped gateway code "INSUFFICIENT_FUNDS"
        PaymentFailure failure = new PaymentFailure(
                "pay_1", "order_1", PaymentContext.ONE_TIME_CHECKOUT, 49900,
                PaymentMethod.UPI, Instant.now(), "INSUFFICIENT_FUNDS", "Declined due to low balance"
        );

        FailureDiagnosis diagnosis = diagnosisEngine.diagnose(failure);

        assertNotNull(diagnosis);
        assertEquals(FailureType.INSUFFICIENT_FUNDS, diagnosis.failureType());
        assertEquals(BigDecimal.ONE, diagnosis.confidence());
        assertEquals(DiagnosisSource.GATEWAY_CODE, diagnosis.source());
        assertEquals("Deterministic gateway code lookup match: INSUFFICIENT_FUNDS", diagnosis.evidence());
    }

    @Test
    void diagnosesCleanCodeAliasDeterministicLookup() {
        // Test mapped gateway code alias "BAD_GATEWAY"
        PaymentFailure failure = new PaymentFailure(
                "pay_2", "order_2", PaymentContext.B2B_RECEIVABLE, 1000000,
                PaymentMethod.NETBANKING, Instant.now(), "BAD_GATEWAY", "Internal server error occurred"
        );

        FailureDiagnosis diagnosis = diagnosisEngine.diagnose(failure);

        assertNotNull(diagnosis);
        assertEquals(FailureType.BANK_TECHNICAL_ERROR, diagnosis.failureType());
        assertEquals(BigDecimal.ONE, diagnosis.confidence());
        assertEquals(DiagnosisSource.GATEWAY_CODE, diagnosis.source());
    }

    @Test
    void delegatesFreeTextToGeminiClient() {
        // Null gateway code forces AI routing
        PaymentFailure failure = new PaymentFailure(
                "pay_3", "order_3", PaymentContext.SUBSCRIPTION_RENEWAL, 9900,
                PaymentMethod.CARD, Instant.now(), null, "some raw messy card message"
        );

        FailureDiagnosis expectedAiDiagnosis = new FailureDiagnosis(
                FailureType.CARD_DECLINED, new BigDecimal("0.85"), "Card declined by user profile", DiagnosisSource.LLM_GEMINI
        );
        when(mockGeminiClient.classifyFreeText("some raw messy card message", PaymentContext.SUBSCRIPTION_RENEWAL, PaymentMethod.CARD))
                .thenReturn(expectedAiDiagnosis);

        FailureDiagnosis diagnosis = diagnosisEngine.diagnose(failure);

        assertNotNull(diagnosis);
        assertEquals(FailureType.CARD_DECLINED, diagnosis.failureType());
        assertEquals(new BigDecimal("0.85"), diagnosis.confidence());
        assertEquals(DiagnosisSource.LLM_GEMINI, diagnosis.source());
        verify(mockGeminiClient).classifyFreeText("some raw messy card message", PaymentContext.SUBSCRIPTION_RENEWAL, PaymentMethod.CARD);
    }

    @Test
    void fallbackGracefullyWhenGeminiKeyIsMissing() {
        // Real client instances under fallback configuration (no API key set)
        GeminiClient client = new GeminiClient(new ObjectMapper());
        client.setApiKey(null); // Missing key simulation
        DiagnosisEngine engine = new DiagnosisEngine(client);

        PaymentFailure failure = new PaymentFailure(
                "pay_4", "order_4", PaymentContext.ONE_TIME_CHECKOUT, 20000,
                PaymentMethod.UPI, Instant.now(), null, "user aborted transaction screen"
        );

        FailureDiagnosis diagnosis = engine.diagnose(failure);

        assertNotNull(diagnosis);
        assertEquals(FailureType.UNKNOWN, diagnosis.failureType());
        assertEquals(BigDecimal.ZERO, diagnosis.confidence());
        assertEquals(DiagnosisSource.MOCK_FALLBACK, diagnosis.source());
        assertTrue(diagnosis.evidence().contains("Fallback: API key is missing"));
    }
}
