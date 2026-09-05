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

## Defense in Depth: The Deterministic Firewall

A common trap in AI finance tools is letting an LLM make routing or financial decisions. LLMs are probabilistic text generators; they will eventually hallucinate a refund, waive a fee, or retry a dead card if given control flow over money.

This architecture explicitly quarantines the AI. 
The `GeminiClient` is strictly bounded to **read-only text classification** (Diagnosis) and **text generation** (Wording). The output of the AI is always caught by the `PolicyEngine`—a strict, 100% deterministic Java firewall. 

If the model confabulates an unknown failure type, or the API rate-limits, the firewall safely rejects it, logs a `MOCK_FALLBACK`, and halts automated action. **The AI never touches the ledger.**
