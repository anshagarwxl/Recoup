# Recoup (Vasooli) — Project Context & Documentation

This document serves as the single source of truth for the project. It describes the project goals, architecture, core decisions (including recent updates regarding API integrations and fallback handling), current development status, and the roadmap ahead.

---

## 1. Executive Summary & Problem Statement

### The Problem
Merchants lose significant recoverable revenue when payment failures are handled using generic, repeated, or poorly-timed retry schemes. This is especially true for:
- UPI mandate renewals.
- Subscription billing cycles.
- High-value B2B invoicing/receivables.

Recovery actions must be tailored to *why* a payment failed (e.g., distinguishing an inactive UPI mandate from a temporary bank timeout or simple lack of funds).

### Product Vision
**Recoup** (working name: *Vasooli*) is an automated payment failure recovery helper for merchant Payment/Revenue Operations teams. It processes batches of failed payments, diagnoses the root cause, applies deterministic policies with hard stopping rules, tracks actions/costs in paise, and produces a scannable audit trail showing the **net** recovered revenue.

### Non-Goals
- Initiating real payment-gateway retries.
- Actually sending SMS/WhatsApp/Voice messages (these are simulated).
- Multi-merchant tenancy or compliance-governed document management.

---

## 2. Key Architecture & Data Flow

```
Synthetic Payment Batch (100–150 failed txns)
         │
         ▼
┌─────────────────────────────────┐
│ 1. Diagnosis Engine             │ ◄── Rule-based Lookup (Gateway Codes)
│                                 │ ◄── LLM Adapter (Ambiguous Free-text via Gemini)
└────────────────┬────────────────┘
                 │
                 │ failure_type, confidence, source (GATEWAY_CODE | GEMINI | MOCK_FALLBACK)
                 ▼
┌─────────────────────────────────┐
│ 2. Policy / Decision Engine     │ ◄── Deterministic Rules (No LLMs here)
│                                 │ ◄── Maps failure type to scheduled actions
└────────────────┬────────────────┘
                 │
                 │ planned_actions & scheduled times
                 ▼
┌─────────────────────────────────┐
│ 3. Mock Recovery Executor       │ ◄── Simulates outcome & tracks paise costs
│                                 │ ◄── Generates reminder wording (with Gemini)
└────────────────┬────────────────┘
                 │
                 │ execution result & cost
                 ▼
┌─────────────────────────────────┐
│ 4. Audit Trail & Reporting      │ ◄── Thymeleaf HTML UI
│                                 │ ◄── Net Recovery = Gross Recovery - Action Costs
└─────────────────────────────────┘
```

---

## 3. Core Engineering Decisions

| Dimension | Selected Approach | Rationale & Alternatives Considered |
|---|---|---|
| **Tech Stack** | Java 17, Spring Boot 3.3.8 | Familiar structure, robust typing for rules, simple dependency management. Considered Python but opted for developer confidence. |
| **User Interface** | Server-rendered Thymeleaf | Fast local development cycle, keeps the app as a single deployable artifact. |
| **Integration Pattern** | Plain Java `HttpClient` | Keeps the application lightweight; no need for a heavy external SDK or Spring REST framework integration just for AI calls. |
| **API Fallback Strategy** | Graceful degradation | If `GEMINI_API_KEY` is missing, or if Gemini calls encounter a timeout, rate limit, or malformed/empty response, the system falls back gracefully. |
| **Ambiguity Resolution** | "Needs Manual Review" | When Gemini fails/is disabled and the input is an ambiguous free-text failure reason, the system will **not** guess using keywords. It will explicitly tag the case as `UNKNOWN` (meaning "needs manual review") with a source tag of `MOCK_FALLBACK`. |
| **Deterministic Rules** | Policy Engine controls money | AI is strictly restricted to text classification (Diagnosis) and reminder text generation (Executor). Decision routes and stopping rules are 100% deterministic code. |
| **Paise Currency** | Non-negative integer paise | Avoids floating-point math errors. Currency is fixed to INR. |

---

## 4. Current Implementation Status

### Completed
* **Scaffolding**: Spring Boot app initialized and compiles cleanly.
* **Domain Model Layer**: Core immutable entities created in [`com.ansh.recoup.domain`](file:///Users/anshagarwal/Desktop/Recoup/src/main/java/com/ansh/recoup/domain):
  * [`PaymentFailure`](file:///Users/anshagarwal/Desktop/Recoup/src/main/java/com/ansh/recoup/domain/PaymentFailure.java): Input event (amount, context, gateway codes).
  * [`FailureDiagnosis`](file:///Users/anshagarwal/Desktop/Recoup/src/main/java/com/ansh/recoup/domain/FailureDiagnosis.java): Root cause categorization.
  * [`RecoveryPlan`](file:///Users/anshagarwal/Desktop/Recoup/src/main/java/com/ansh/recoup/domain/RecoveryPlan.java) / [`PlannedAction`](file:///Users/anshagarwal/Desktop/Recoup/src/main/java/com/ansh/recoup/domain/PlannedAction.java): Deterministic schedule.
  * [`ActionExecution`](file:///Users/anshagarwal/Desktop/Recoup/src/main/java/com/ansh/recoup/domain/ActionExecution.java): Mock results and paise cost tracker.
  * [`AuditEvent`](file:///Users/anshagarwal/Desktop/Recoup/src/main/java/com/ansh/recoup/domain/AuditEvent.java) / [`RecoveryCase`](file:///Users/anshagarwal/Desktop/Recoup/src/main/java/com/ansh/recoup/domain/RecoveryCase.java): Aggregate representing the audit trail.
* **Tests**: Verified basic validation constraints (negative amount/cost checks, chronological execution checks) in [`RecoveryDataSchemaTest`](file:///Users/anshagarwal/Desktop/Recoup/src/test/java/com/ansh/recoup/domain/RecoveryDataSchemaTest.java).
* **Failures Logged**: Resolved Mockito self-attaching inline maker bug in the local Maven environment (documented in [FAILURES.md](file:///Users/anshagarwal/Desktop/Recoup/docs/FAILURES.md)).

---

## 5. Detailed Architecture & Design Constraints

### The API Fallback & Diagnosis Logic
1. **Lookup Table (Gateway Code)**:
   If a `PaymentFailure` contains a recognized structured gateway code (e.g., `UPI_MANDATE_EXPIRED`, `INSUFFICIENT_FUNDS`), it is classified immediately with 100% confidence. Source = `GATEWAY_CODE`.
2. **Gemini Engine**:
   If the failure reason is ambiguous free-text (e.g., *"debit failed, server rejected"*), the system makes a POST request to the Gemini Flash endpoint using plain Java `HttpClient`.
   * If successful, the return payload is parsed. Source = `GEMINI`.
3. **Mock Fallback**:
   If the Gemini call fails for any of the following reasons:
   * Missing `GEMINI_API_KEY`
   * Socket Timeout / Connection Timeout
   * Rate Limit (HTTP 429)
   * Malformed or empty response
   The system falls back gracefully. It does **not** guess keywords. Instead, it classifies the case as `UNKNOWN` (meaning "needs manual review") with a source tag of `MOCK_FALLBACK`. This state is rendered explicitly in reports and audit trails.

### Audit Trail Tagging
Every diagnosis and message generated must record and display its origin:
* `GATEWAY_CODE` — Deterministic parsing of standard gateway codes.
* `GEMINI` — Successful AI categorization/wording.
* `MOCK_FALLBACK` — Gracefully degraded diagnosis ("Needs Manual Review") or system-default messaging due to API issues.

---

## 6. The Road Ahead & Next Steps

1. **Step 1: Synthetic Data Layer (Data Generator)**
   * Code a seeded generator (`com.ansh.recoup.data.SyntheticDataGenerator`) to produce a reproducible batch of 100–150 failed payments across various methods (UPI-majority, Cards, Netbanking) and contexts.
2. **Step 2: Diagnosis Engine**
   * Code the gateway mapping lookup table.
   * Write the plain `HttpClient`-based `GeminiClient` with JSON parsing and complete try-catch error/fallback handling.
3. **Step 3: Policy Engine**
   * Program rules for mapping failure classifications to recovery schedules and stopping conditions.
4. **Step 4: Mock Recovery Executor**
   * Simulate payment retries and communication actions, applying cost rates.
5. **Step 5: HTML Dashboard & Audit Trail UI**
   * Set up Thymeleaf controllers and templates to display the batch summary (Gross vs. Net recovered revenue) and timelines.