# Recoup

**Recoup** is an auditable recovery orchestration system for batches of failed or at-risk payments. It diagnoses payment failures, applies deterministic and compliant recovery policies, simulates bounded recovery actions, and reports net recovered revenue with a transaction-level audit trail.

Built for the Razorpay AI Buildathon — Track 03: AI Revenue Recovery.

## Product principles

- **UPI-aware by design:** payment failure handling reflects Indian payment realities rather than treating every failure as a card decline.
- **Deterministic where money is involved:** policies, stopping rules, costs, and escalations are explicit, testable code.
- **AI only where it adds judgment:** ambiguous text classification and template-bound message drafting.
- **Honest recovery metrics:** report revenue recovered after retry, messaging, and inference costs.
- **Auditable decisions:** every diagnosis, decision, action, and outcome has a human-readable explanation.

## Technology

- Java 17
- Spring Boot
- Thymeleaf local web UI
- JUnit 5
- Gemini Flash REST integration (planned; optional at runtime)

## Status

Project bootstrap complete. The next milestone is defining the domain model and producing a reproducible synthetic dataset.

## Local development

```bash
./mvnw spring-boot:run
```

Run tests with:

```bash
./mvnw test
```

## Documentation

- [Problem framing](docs/PROBLEM.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Engineering decisions](docs/DECISIONS.md)
- [Data methodology](docs/DATA.md)
- [Failures and fixes](docs/FAILURES.md)

