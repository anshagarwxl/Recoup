package com.recoup.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Validates key environment configurations at system startup. */
@Component
public class SecretValidator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretValidator.class);

    @Value("${GEMINI_API_KEY:}")
    private String apiKey;

    @Override
    public void run(String... args) {
        log.info("==================================================");
        log.info("Recoup (Vasooli) System Startup Validation Check");
        log.info("==================================================");

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("WARNING: 'GEMINI_API_KEY' is NOT set in the environment or properties.");
            log.warn("The application will operate in GRACEFUL FALLBACK mode.");
            log.warn("Ambiguous free-text errors will map to UNKNOWN and be tagged as MOCK_FALLBACK.");
        } else {
            log.info("SUCCESS: 'GEMINI_API_KEY' is configured.");
            log.info("Gemini Flash integration is ACTIVE for free-text diagnosis.");
        }
        log.info("==================================================");
    }
}
