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

1. **[COMPLETED] Step 1: Synthetic Data Layer (Data Generator)**
   * Seeded generator (`com.recoup.generator.SyntheticDataGenerator`) produces a reproducible batch of 100–150 failed payments across UPI, Cards, and Netbanking.
2. **[COMPLETED] Step 2: Diagnosis Engine**
   * Deterministic lookup table maps clean codes.
   * `GeminiClient` connects via plain `HttpClient` with 5s timeout and structured JSON generation configs.
   * Graceful fallback triggers on timeout, HTTP 429, missing key, or parse exceptions (resolves to UNKNOWN and MOCK_FALLBACK without guessing).
3. **[COMPLETED] Step 3: Policy Engine**
   * Program rules for mapping failure classifications to recovery schedules, estimated costs, and stopping conditions, including a high-value manual escalation overlay (> ₹10,000).
4. **[COMPLETED] Step 4: Mock Recovery Executor**
   * Simulates payment retries and communication actions, applying step success rates and cost accounting rules (actual cost charged on attempts, zero cost when skipped).
5. **[COMPLETED] Step 5: HTML Dashboard & Audit Trail UI**
   * Built Thymeleaf dashboard and Spring MVC controller to display batch KPI metrics (Gross vs. Net recovery), case filter lists, and interactive transaction audit trail inspection drawers.

---

## 7. Chronological Handover & Session Log

### Session 1 (2026-08-25) — Bootstrapping & Phase 1 Core Backend
* **Actions Taken**:
  * Rewrote `docs/Context.md` and created `AGENTS.md` to define guidelines (Karpathy rules, ECC file bounds, deterministic policies, context log requirements).
  * Implemented and validated the core schema domain records under `com.recoup.domain`.
  * **[Phase 1 Implementation]**:
    * Created `SyntheticDataGenerator.java` mapping 15 realistic failure profiles (UPI-majority context weights) using a seeded `Random` generator.
    * Created `GeminiClient.java` using plain Java `HttpClient` to call Gemini Flash (configured with a 5-second timeout, `responseMimeType: "application/json"`, and complete try-catch degradation logic).
    * Created `DiagnosisEngine.java` to perform deterministic mappings for known code lookups, falling back to `GeminiClient` for free-text, and resolving to `MOCK_FALLBACK` on failures.
    * Created `SecretValidator.java` as a non-blocking startup check for environment variables.
    * Wrote unit tests in `SyntheticDataGeneratorTest.java` and `DiagnosisEngineTest.java` verifying reproducibility, target counts, and error-fallback cases.
  * Committed all code changes and pushed them to GitHub.

### Session 2 (2026-08-28) — Refactoring & Phase 2 Core Backend
* **Actions Taken**:
  * Renamed project packages globally from `com.ansh.recoup` to `com.recoup` and updated the `pom.xml` group ID coordinate.
  * Created `docs/EXPLANATIONS.md` containing detailed walkthroughs of Phase 1 and 2 files, and added it to `.gitignore`.
  * Added the Pair-Programming & Walkthrough Discipline rule to `AGENTS.md`.
  * Modified domain schema models:
    * Added `HARD_DECLINE` to `FailureType`.
    * Added `long costPaise` to `PlannedAction` to support cost tracking of scheduled interventions.
    * Added `UNRESOLVED` to `RecoveryStatus` to distinguish tried-and-failed cases from compliance halts.
  * Implemented `PolicyEngine.java` to schedule recovery paths, estimated costs, and manual escalation overlays.
  * Implemented `RecoveryExecutor.java` to simulate execution outcomes using a configurable seeded `Random` and apply actual cost charging rules.
  * Documented outcome probabilities and assumptions in `docs/DATA.md`.
  * Verified build compiles and all 20 tests pass with `./mvnw test`.
  * Committed refactoring and Policy Engine changes locally (commits `715929b`, `0fb6668` and `dbad53d`) and pushed them to GitHub.

### Session 3 (2026-08-29) — Pipeline Orchestration & Interactive Web UI
* **Actions Taken**:
  * Implemented `RecoveryOrchestrator.java` to coordinate the full end-to-end recovery lifecycle (diagnosis -> policy -> execution with early-stopping and audit trail construction).
  * Implemented `RecoveryMetrics.java` to aggregate financial KPIs (Gross revenue, actual intervention costs, Net yield).
  * Implemented `TimelineFormatter.java` for simulated relative offset formatting in the backend audit logs.
  * Implemented `DashboardController.java` to serve the web dashboard and REST case inspection API.
  * Built `dashboard.html` with modern dark-mode fintech aesthetics, metric cards, status filters, search, and slide-over audit trail drawers.
  * Created `RecoveryOrchestratorTest.java` and `DashboardControllerTest.java` bringing the test suite to 25 passing automated tests.
  * Created `docs/PROJECT_GUIDE.md` as an in-depth understanding guide.
  * Verified build compiles and all 25 tests pass (`./mvnw clean test`).
* **Next Task**:
  * Run application locally (`./mvnw spring-boot:run`) and verify web dashboard functionality.

### Session 4 (2026-08-29) — Final Polish, Thread-Safety & Export Artifacts
* **Actions Taken**:
  * Replaced vanilla text with interactive **Chart.js** visualizations (Status distribution, Diagnosis Source, and Failure Type breakdown). Bundled Chart.js locally for offline-safe demos.
  * Enhanced `dashboard.html` with a scrollable 125-row table (with sticky headers), native `@media print` CSS for exporting clean PDF snapshots, a new SVG Refresh arrow logo, and JavaScript-based relative timestamps for the UI timeline drawer.
  * Refactored `DashboardController.java` to be entirely stateless using an immutable `DashboardState` record per request, eliminating multithreading risks during concurrent batch refreshes.
  * Componentized `SyntheticDataGenerator.java` via Spring `@Component`.
  * Extracted audit string formatting into `AuditTrailBuilder.java` to simplify `RecoveryOrchestrator.java`.
  * Verified all 25 tests still pass cleanly.
* **Next Task**:
  * User to configure `GEMINI_API_KEY` and record the final demonstration video.