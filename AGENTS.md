# AI Agent Guidelines & Coding Standards (AGENTS.md)

This document is loaded by AI coding assistants to align behavior, ensure code quality, maintain security, and enforce architecture constraints.

---

## 1. Andrej Karpathy Skills (Behavioral Etiquette)

- **Think Before Coding**: Before making edits, explicitly state the interpretation of the task, call out assumptions, and highlight design trade-offs.
- **Simplicity First**: Implement the smallest, most direct change that satisfies the request. Do not write bloated abstractions or speculate on future features.
- **Surgical Changes**: Modify only what is strictly necessary. Avoid "drive-by" refactorings of unrelated code that is not broken.
- **Goal-Driven Loops**: Define clear, verifiable success criteria (like unit tests or CLI execution) and verify correctness before completing the task.
- **Honesty**: Avoid overconfident code, silently assumed behavior, or unverified claims. If something is uncertain, ask the user or write a test to confirm.

---

## 2. ECC Configuration & File-Organization

- **Security Boundaries**:
  - Never hardcode API keys, credentials, or secrets in code or configuration files.
  - Load all sensitive configurations via environment variables.
  - Validate that required secrets (e.g., `GEMINI_API_KEY`) are present and non-blank during system startup.
- **File Length & Cohesion**:
  - Favor many small, highly cohesive files over a few large ones.
  - Target file length is **200–400 lines**; files must never exceed **800 lines**.
  - Package classes by domain/feature (e.g., `diagnosis`, `policy`, `executor`, `reporting`) instead of technical layers (e.g., `controller`, `service`, `model`).

---

## 3. Git Discipline & Commit Checkpoints

- **Atomic Commits**: Commit code on your own initiative at every meaningful checkpoint (e.g., a domain model change, a single engine implementation, a bug fix, or a docs update).
- **Commit Message Clarity**: Write clear, imperative, and descriptive commit messages (e.g., `feat: implement seeded synthetic payment generator` or `docs: update fallback logic in context`). Avoid generic messages like `update code` or `fixes`.
- **Run-Ready State**: Verify that the repository compiles and all unit tests pass before committing. Do not commit broken main-branch code.

---

## 4. Hard Architecture Constraints

- **Deterministic Decisions**: The policy and decision engines (handling failure types, selecting recovery actions, stopping rules, and escalation rules) must be written in **pure deterministic Java code**. Do not delegate financial or workflow decisions to an LLM.
- **Restricted LLM Scope**: Gemini Flash is used **only** for:
  1. Classifying raw, ambiguous free-text failure reasons when gateway codes are absent.
  2. Generating the wording for recovery messages (nudge copy).
- **Graceful Fallback**: If Gemini encounters an issue (missing API key, rate limit, timeout, malformed payload), the system must degrade gracefully. The failure must be categorized as `UNKNOWN` (Needs Manual Review) and tagged as `MOCK_FALLBACK`.
- **Audit Trails**: Every transaction’s entire path (original failure -> diagnosis -> policy plan -> action execution -> outcome) must be logged in a structured audit trail, with the source of the diagnosis (`GATEWAY_CODE`, `LLM_GEMINI`, or `MOCK_FALLBACK`) clearly stored and visible in reports.

---

## 5. Documentation Handover & Context Discipline

- **Living Context File (`docs/Context.md`)**: The file `docs/Context.md` is our primary mechanism for cross-session and cross-agent handover.
- **Continuous Updates**: After completing any minor or major milestone, or when significant ideation changes occur, the active agent must completely rewrite or update `docs/Context.md` to reflect the exact current development stage, status of all files, and decisions.
- **Running Log**: Maintain a running chronological history log in `docs/Context.md` summarizing what has been done, so that if a token limit requires switching agents, the next agent can immediately resume work without context loss.

