package com.ansh.recoup.generator;

import com.ansh.recoup.domain.PaymentContext;
import com.ansh.recoup.domain.PaymentFailure;
import com.ansh.recoup.domain.PaymentMethod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Generates a reproducible, seeded batch of synthetic payment failures. */
public class SyntheticDataGenerator {

    private static final Instant BASE_TIME = Instant.parse("2026-08-25T00:00:00Z");

    private static final List<FailureTemplate> UPI_TEMPLATES = List.of(
            new FailureTemplate(PaymentContext.SUBSCRIPTION_RENEWAL, 49_900, 149_900, "INSUFFICIENT_FUNDS", "Insufficient balance in user account"),
            new FailureTemplate(PaymentContext.ONE_TIME_CHECKOUT, 5_000, 25_000, "UPI_COLLECT_EXPIRED", "UPI collect request expired after 10 minutes"),
            new FailureTemplate(PaymentContext.SUBSCRIPTION_RENEWAL, 29_900, 99_900, "UPI_MANDATE_INACTIVE", "Mandate is inactive or suspended"),
            new FailureTemplate(PaymentContext.ONE_TIME_CHECKOUT, 1_000, 50_000, "UPI_PIN_INCORRECT", "Incorrect UPI PIN entered by user"),
            new FailureTemplate(PaymentContext.ONE_TIME_CHECKOUT, 10_000, 150_000, null, "debit failed: transaction was terminated by user on app"),
            new FailureTemplate(PaymentContext.SUBSCRIPTION_RENEWAL, 49_900, 249_900, null, "remittance failed: technical error on payee bank side")
    );

    private static final List<FailureTemplate> CARD_TEMPLATES = List.of(
            new FailureTemplate(PaymentContext.SUBSCRIPTION_RENEWAL, 99_900, 499_900, "CARD_EXPIRED", "Card validity has expired"),
            new FailureTemplate(PaymentContext.ONE_TIME_CHECKOUT, 20_000, 1_000_000, "AUTHENTICATION_FAILED", "3DS verification failed or timed out"),
            new FailureTemplate(PaymentContext.ONE_TIME_CHECKOUT, 50_000, 2_000_000, "CARD_DECLINED", "Card declined by issuing bank"),
            new FailureTemplate(PaymentContext.ONE_TIME_CHECKOUT, 200_000, 5_000_000, null, "declined due to suspected risk profile"),
            new FailureTemplate(PaymentContext.SUBSCRIPTION_RENEWAL, 49_900, 299_900, null, "network failure while contacting visa directory server")
    );

    private static final List<FailureTemplate> NETBANKING_TEMPLATES = List.of(
            new FailureTemplate(PaymentContext.B2B_RECEIVABLE, 500_000, 10_000_000, "PAYMENT_TIMEOUT", "Bank gateway did not respond within timeout limit"),
            new FailureTemplate(PaymentContext.B2B_RECEIVABLE, 100_000, 5_000_000, "BANK_TECHNICAL_ERROR", "Internal server error at netbanking portal"),
            new FailureTemplate(PaymentContext.B2B_RECEIVABLE, 500_000, 50_000_000, null, "customer aborted netbanking login screen"),
            new FailureTemplate(PaymentContext.B2B_RECEIVABLE, 1_000_000, 100_000_000, null, "system outage at state bank of india netbanking API")
    );

    /**
     * Generates a batch of failed payments.
     *
     * @param size number of records to generate.
     * @param seed the random seed for reproducibility.
     * @return list of generated PaymentFailure records.
     */
    public List<PaymentFailure> generateBatch(int size, long seed) {
        Random random = new Random(seed);
        List<PaymentFailure> failures = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            // Determine payment method using targeted weights:
            // ~65% UPI, ~20% Card, ~15% Netbanking
            int r = random.nextInt(100);
            PaymentMethod method;
            FailureTemplate template;

            if (r < 65) {
                method = PaymentMethod.UPI;
                template = UPI_TEMPLATES.get(random.nextInt(UPI_TEMPLATES.size()));
            } else if (r < 85) {
                method = PaymentMethod.CARD;
                template = CARD_TEMPLATES.get(random.nextInt(CARD_TEMPLATES.size()));
            } else {
                method = PaymentMethod.NETBANKING;
                template = NETBANKING_TEMPLATES.get(random.nextInt(NETBANKING_TEMPLATES.size()));
            }

            String paymentId = String.format("pay_%05d_%d", i + 1, random.nextInt(90000) + 10000);
            String merchantReference = String.format("ref_%d_%d", 100000 + i, random.nextInt(900000) + 100000);

            // Generate amount within template range
            long amountPaise = template.minAmount + (long) (random.nextDouble() * (template.maxAmount - template.minAmount));
            // Round to nearest 100 paise (nearest rupee) for realistic pricing structure
            amountPaise = (amountPaise / 100) * 100;
            if (amountPaise <= 0) {
                amountPaise = template.minAmount;
            }

            // Stagger failed timestamps incrementally over a few days
            Instant failedAt = BASE_TIME.plusSeconds(i * 1200L + random.nextInt(600));

            failures.add(new PaymentFailure(
                    paymentId,
                    merchantReference,
                    template.context,
                    amountPaise,
                    method,
                    failedAt,
                    template.gatewayCode,
                    template.gatewayReason
            ));
        }

        return failures;
    }

    private record FailureTemplate(
            PaymentContext context,
            long minAmount,
            long maxAmount,
            String gatewayCode,
            String gatewayReason
    ) {}
}
