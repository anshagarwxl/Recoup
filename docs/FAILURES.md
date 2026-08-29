# Failures and fixes

This is a running, factual engineering log. Add entries only for problems actually encountered during development, including their observed impact, diagnosis, fix, and regression protection.

| Date | Symptom | Root cause | Fix | Regression protection |
| --- | --- | --- | --- | --- |
| 2026-08-25 | `./mvnw test` failed before the context test because Mockito could not self-attach a Byte Buddy agent. | The test runtime selected Mockito's inline mock maker, which requires JVM agent attachment unavailable in the local environment. | Configured Mockito's subclass mock maker for test scope. No current tests require inline mocking. | Full Maven test suite runs without JVM self-attachment. |
| 2026-08-29 | Thymeleaf TemplateInputException during MockMvc rendering of dashboard.html. | String concatenation in `th:onclick` violated standard Thymeleaf DOM event attribute processor security constraints. | Switched from `th:onclick` expression concatenation to `th:attr="data-payment-id=..."` with native `onclick` reader. | DashboardControllerTest parses and renders full Thymeleaf DOM during `./mvnw test`. |
