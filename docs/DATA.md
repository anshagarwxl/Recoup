# Synthetic data methodology

The application will generate a reproducible batch of 100–150 synthetic failed-payment records. The batch will contain a UPI-majority payment-method distribution, alongside cards and netbanking.

It will cover one-time checkout, subscription renewal, and B2B receivable contexts; a range of payment values; clean gateway codes; and a limited set of ambiguous free-text reasons for LLM-fallback evaluation.

All identities, amounts, payment references, and outcomes are synthetic. Batch generation must use an explicit seed so results can be reproduced in tests and in the demo.

