# Engineering decisions

| Date | Decision | Rationale | Alternatives considered |
| --- | --- | --- | --- |
| 2026-08-25 | Java 17 and Spring Boot | Java is the builder's preferred language; Spring Boot provides a familiar, structured local web application. Java 17 is available in the current environment. | Python; Java 21 |
| 2026-08-25 | Server-rendered local UI with Thymeleaf | Keeps the demo deployable as one Java application and avoids frontend build-tool overhead within the buildathon window. | React SPA; CLI-only report |
| 2026-08-25 | Deterministic recovery policy | Money-sensitive decisions must be reproducible, testable, and explainable. | LLM-directed policy |
| 2026-08-25 | AI limited to ambiguity resolution and wording | LLMs add value for free-text interpretation and natural language while avoiding control over financial decisions. | Broad agentic workflow |

