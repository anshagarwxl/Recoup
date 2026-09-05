package com.recoup.diagnosis;

import com.recoup.domain.DiagnosisSource;
import com.recoup.domain.FailureDiagnosis;
import com.recoup.domain.FailureType;
import com.recoup.domain.PaymentContext;
import com.recoup.domain.PaymentMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Lightweight, timeout-bound client to classify ambiguous text failures via Gemini Flash. */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String GEMINI_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=%s";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${GEMINI_API_KEY:}")
    private String apiKey;

    public GeminiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Calls the Gemini API to classify the raw failure text.
     * Degrades gracefully to MOCK_FALLBACK on timeout, rate-limits, malformed response, or missing key.
     */
    public FailureDiagnosis classifyFreeText(String rawReason, PaymentContext context, PaymentMethod method) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key is missing. Falling back to MOCK_FALLBACK.");
            return fallbackDiagnosis("API key is missing");
        }

        try {
            String systemInstruction = "You are a payment failure diagnosis assistant. Your job is to classify payment failures. " +
                    "You must respond with raw JSON matching this schema: " +
                    "{\"failureType\": \"string\", \"confidence\": number, \"evidence\": \"string\"}. " +
                    "The failureType must be exactly one of these enum values: INSUFFICIENT_FUNDS, UPI_MANDATE_INACTIVE, " +
                    "UPI_COLLECT_EXPIRED, BANK_TECHNICAL_ERROR, CARD_DECLINED, AUTHENTICATION_FAILED, PAYMENT_TIMEOUT, UNKNOWN.";

            String prompt = String.format(
                    "Classify this payment failure:\n" +
                    "Payment Method: %s\n" +
                    "Payment Context: %s\n" +
                    "Raw Gateway Message: %s\n\n" +
                    "Instructions: %s",
                    method.name(), context.name(), rawReason, systemInstruction);

            // Construct JSON payload: { contents: [ { parts: [ { text: "..." } ] } ], generationConfig: { responseMimeType: "application/json" } }
            Map<String, Object> textPart = Map.of("text", prompt);
            Map<String, Object> contentNode = Map.of("parts", List.of(textPart));
            Map<String, Object> genConfig = Map.of("responseMimeType", "application/json");
            Map<String, Object> requestPayload = Map.of(
                    "contents", List.of(contentNode),
                    "generationConfig", genConfig
            );

            String jsonRequest = objectMapper.writeValueAsString(requestPayload);
            String apiUrl = String.format(GEMINI_URL_TEMPLATE, apiKey.trim());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warn("Gemini API rate-limit (429) reached. Graceful degradation active.");
                return fallbackDiagnosis("API rate limit reached (HTTP 429)");
            }

            if (response.statusCode() != 200) {
                log.warn("Gemini API returned unsuccessful status code: {}. Body: {}", response.statusCode(), response.body());
                return fallbackDiagnosis("API returned non-200 code: " + response.statusCode());
            }

            // Parse response candidates
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    String innerJson = parts.get(0).path("text").asText();
                    JsonNode innerNode = objectMapper.readTree(innerJson);

                    String failureTypeStr = innerNode.path("failureType").asText("UNKNOWN");
                    double confidence = innerNode.path("confidence").asDouble(0.0);
                    String evidence = innerNode.path("evidence").asText("Unspecified reason");

                    FailureType failureType;
                    try {
                        failureType = FailureType.valueOf(failureTypeStr.trim());
                    } catch (IllegalArgumentException e) {
                        log.warn("Gemini classified invalid FailureType: '{}'. Mapping to UNKNOWN.", failureTypeStr);
                        failureType = FailureType.UNKNOWN;
                    }

                    return new FailureDiagnosis(
                            failureType,
                            BigDecimal.valueOf(confidence),
                            evidence,
                            DiagnosisSource.LLM_GEMINI
                    );
                }
            }

            log.warn("Gemini API response was malformed or empty candidates. Body: {}", response.body());
            return fallbackDiagnosis("Empty or malformed candidates response");

        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("Gemini API request timed out (5s cap). Graceful degradation active.");
            return fallbackDiagnosis("Request timed out (5s)");
        } catch (Exception e) {
            log.warn("Failed to complete Gemini API diagnosis due to an exception: {}", e.getMessage(), e);
            return fallbackDiagnosis("Exception occurred: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private FailureDiagnosis fallbackDiagnosis(String note) {
        return new FailureDiagnosis(
                FailureType.UNKNOWN,
                BigDecimal.ZERO,
                "Needs Manual Review (Fallback: " + note + ")",
                DiagnosisSource.MOCK_FALLBACK
        );
    }

    // Visible for testing to mock API key overrides
    void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
