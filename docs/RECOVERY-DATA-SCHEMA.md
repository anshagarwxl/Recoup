# Recovery data schema

This is the application-level schema for a single failed-payment recovery case. It is intentionally immutable and persistence-agnostic for the buildathon prototype. A future database adapter may map these types to tables without changing the policy-facing model.

```
PaymentFailure (the input event)
        |
        +-- FailureDiagnosis (what failed, and why)
        |
        +-- RecoveryPlan (allowed actions, scheduled actions, stopping rationale)
        |
        +-- ActionExecution[] (simulated or real action outcomes and costs)
        |
        +-- AuditEvent[] (chronological, human-readable decision trail)
```

## Entities

| Type | Purpose | Core fields |
| --- | --- | --- |
| `PaymentFailure` | Original failed payment, never mutated by recovery logic. | payment ID, merchant reference, context, amount in paise, payment method, failure time, gateway code and reason |
| `FailureDiagnosis` | Normalized interpretation of the failure. | failure type, confidence, evidence, diagnosis source |
| `RecoveryPlan` | Deterministic policy output for one failure. | planned actions, recovery status, stopping rationale |
| `PlannedAction` | An action the policy allows at a precise time. | action type, scheduled time, rationale, cost in paise |
| `ActionExecution` | Result and cost of an attempted action. | planned action, result, executed time, cost in paise, outcome note |
| `AuditEvent` | An append-only explanation suitable for the UI. | timestamp, event type, message |

## Constraints

- Currency is currently constrained to INR and all money uses non-negative integer paise; no floating-point currency is permitted.
- IDs and reasons must be non-blank. Gateway failure codes are optional because some synthetic ambiguous cases intentionally lack a clean code.
- Confidence is a decimal in the inclusive range `[0, 1]`.
- A recovery plan must contain at least one planned action and cannot be marked recovered before an execution succeeds.
- Execution costs are non-negative, and an execution cannot happen before its scheduled time.

`PaymentMethod`, `PaymentContext`, `FailureType`, `DiagnosisSource`, `RecoveryActionType`, `RecoveryStatus`, `ActionResult`, and `AuditEventType` are closed enums. This makes policy inputs exhaustive and prevents accidental free-text states.
