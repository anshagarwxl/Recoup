# Architecture

```
Synthetic payment batch
        |
        v
Diagnosis engine ----> normalized failure type + confidence
        |
        v
Deterministic policy engine ----> allowed actions, schedule, stopping rationale
        |
        v
Mock recovery executor ----> action outcomes + per-action costs
        |
        v
Metrics and audit projection ----> batch dashboard + transaction timeline
```

## Boundaries

- The diagnosis engine uses direct mappings for known codes. The Gemini Flash REST client (`GeminiClient`) handles ambiguous free text with graceful mock fallback.
- The policy engine never delegates recovery, escalation, or stopping decisions to an LLM.
- The executor simulates external effects behind interfaces, allowing real integrations to remain explicitly out of scope.
- The UI reads a view-oriented representation of the audit log; it must not contain policy logic.

