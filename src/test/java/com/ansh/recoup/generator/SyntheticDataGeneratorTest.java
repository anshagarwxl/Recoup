package com.ansh.recoup.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ansh.recoup.domain.PaymentFailure;
import com.ansh.recoup.domain.PaymentMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticDataGeneratorTest {

    @Test
    void generatesReproducibleBatches() {
        SyntheticDataGenerator generator = new SyntheticDataGenerator();
        long seed = 12345L;

        List<PaymentFailure> batch1 = generator.generateBatch(120, seed);
        List<PaymentFailure> batch2 = generator.generateBatch(120, seed);

        assertEquals(120, batch1.size());
        assertEquals(120, batch2.size());

        for (int i = 0; i < 120; i++) {
            PaymentFailure f1 = batch1.get(i);
            PaymentFailure f2 = batch2.get(i);

            assertEquals(f1.paymentId(), f2.paymentId());
            assertEquals(f1.merchantReference(), f2.merchantReference());
            assertEquals(f1.context(), f2.context());
            assertEquals(f1.amountPaise(), f2.amountPaise());
            assertEquals(f1.paymentMethod(), f2.paymentMethod());
            assertEquals(f1.failedAt(), f2.failedAt());
            assertEquals(f1.gatewayFailureCode(), f2.gatewayFailureCode());
            assertEquals(f1.gatewayFailureReason(), f2.gatewayFailureReason());
        }
    }

    @Test
    void respectsTargetSizesAndDistributions() {
        SyntheticDataGenerator generator = new SyntheticDataGenerator();
        List<PaymentFailure> batch = generator.generateBatch(150, 42L);

        assertEquals(150, batch.size());

        long upiCount = 0;
        long cardCount = 0;
        long nbCount = 0;
        long cleanCodesCount = 0;
        long ambiguousCodesCount = 0;

        for (PaymentFailure f : batch) {
            assertNotNull(f.paymentId());
            assertFalse(f.paymentId().isBlank());
            assertNotNull(f.merchantReference());
            assertFalse(f.merchantReference().isBlank());
            assertTrue(f.amountPaise() > 0);
            assertNotNull(f.context());
            assertNotNull(f.failedAt());
            assertNotNull(f.gatewayFailureReason());

            if (f.paymentMethod() == PaymentMethod.UPI) {
                upiCount++;
            } else if (f.paymentMethod() == PaymentMethod.CARD) {
                cardCount++;
            } else if (f.paymentMethod() == PaymentMethod.NETBANKING) {
                nbCount++;
            }

            if (f.gatewayFailureCode() != null) {
                cleanCodesCount++;
            } else {
                ambiguousCodesCount++;
            }
        }

        // Verify weights target ~65% UPI, ~20% Card, ~15% Netbanking
        // For a size of 150:
        // UPI should be dominant (>50%)
        // Cards and NB should be present and healthy (>10 records each)
        assertTrue(upiCount > 75, "UPI count should be dominant: " + upiCount);
        assertTrue(cardCount > 15, "Card count should be healthy: " + cardCount);
        assertTrue(nbCount > 10, "Netbanking count should be healthy: " + nbCount);

        // Verify we have a mix of clean codes and ambiguous (null) codes
        assertTrue(cleanCodesCount > 30, "Clean codes count: " + cleanCodesCount);
        assertTrue(ambiguousCodesCount > 30, "Ambiguous codes count: " + ambiguousCodesCount);
    }
}
