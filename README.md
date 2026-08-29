# Recoup 💰

![Java 17](https://img.shields.io/badge/Java-17-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.8-brightgreen.svg)
![Razorpay Buildathon](https://img.shields.io/badge/Razorpay-Track_03-blue.svg)

**Recoup** is an auditable recovery orchestration system for batches of failed or at-risk payments. It diagnoses payment failures, applies deterministic and compliant recovery policies, simulates bounded recovery actions, and reports net recovered revenue with a transaction-level audit trail.

Built for the **Razorpay AI Buildathon — Track 03: AI Revenue Recovery**.

---

## 🎥 Demo Video

> **[Insert Link to ~5-minute Demo Video Here]**
*(Note: Watch the video to see the live Gemini Flash AI classification and the dynamic timeline drawer in action).*

---

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
- Gemini Flash REST integration (active; gracefully optional at runtime)

## Status

All milestones are complete: Seeded synthetic data generator, diagnosis engine (gateway code lookups + Gemini Flash REST client), deterministic policy engine (compliance halts & high-value escalation overlay), simulated recovery executor, and interactive Thymeleaf web dashboard with real-time filters and transaction audit trail drawers. Complete test suite with 25 passing unit and MVC tests.

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
- [Recovery data schema](docs/RECOVERY-DATA-SCHEMA.md)
- [Failures and fixes](docs/FAILURES.md)
