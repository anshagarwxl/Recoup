# Synthetic data methodology

The application will generate a reproducible batch of 100–150 synthetic failed-payment records. The batch will contain a UPI-majority payment-method distribution, alongside cards and netbanking.

It will cover one-time checkout, subscription renewal, and B2B receivable contexts; a range of payment values; clean gateway codes; and a limited set of ambiguous free-text reasons for LLM-fallback evaluation.

All identities, amounts, payment references, and outcomes are synthetic. Batch generation must use an explicit seed so results can be reproduced in tests and in the demo.

## Outcome Simulation Probabilities

To evaluate batch recovery performance, the execution engine simulates action outcomes using the following conservative step-by-step success rates:
- **RETRY_PAYMENT (Insufficient Funds)**: 15% success rate (reflects customer delay in topping up balances).
- **RETRY_PAYMENT (Bank Technical Outage / Timeout)**: 50% success rate (reflects typical resolution windows for brief gateway outages).
- **SEND_UPI_REMINDER**: 25% success rate (reflects users acting on notification nudges).
- **SEND_PAYMENT_LINK**: 30% success rate (reflects invoice or checkout recovery via secure payment links).
- **ESCALATE_TO_ACCOUNT_MANAGER**: 40% success rate (reflects direct manual outreach for high-value receivables).

*Note on Benchmarks*: These step-by-step probabilities are deliberately conservative, standalone assumptions for local simulation. They are not calibrated to match commercial dunning benchmarks or cumulative recovery rates.

