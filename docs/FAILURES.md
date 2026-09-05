# Failures and fixes

This is a running, factual engineering log. Add entries only for problems actually encountered during development, including their observed impact, diagnosis, fix, and regression protection.

| Date | Symptom | Root cause | Fix | Regression protection |
| --- | --- | --- | --- | --- |
| 2026-08-25 | `./mvnw test` failed before the context test because Mockito could not self-attach a Byte Buddy agent. | The test runtime selected Mockito's inline mock maker, which requires JVM agent attachment unavailable in the local environment. | Configured Mockito's subclass mock maker for test scope. No current tests require inline mocking. | Full Maven test suite runs without JVM self-attachment. |
| 2026-08-29 | Thymeleaf TemplateInputException during MockMvc rendering of dashboard.html. | String concatenation in `th:onclick` violated standard Thymeleaf DOM event attribute processor security constraints. | Switched from `th:onclick` expression concatenation to `th:attr="data-payment-id=..."` with native `onclick` reader. | DashboardControllerTest parses and renders full Thymeleaf DOM during `./mvnw test`. |
| 2026-09-05 | Dashboard shows 0 AI calls, getting spammed with 429s in logs. | App was firing off 30+ Gemini requests at once on startup, instantly hitting the free tier limit. | Added a simple rate limiter in `GeminiClient` to cap at 3 calls per run. | Fallback logic works as expected when limits are hit. |
| 2026-09-05 | Still getting 429s even with the rate limiter. | Turns out the `3.6-flash` free tier only gives 20 requests a day and we used them all up testing. | Switched the model to `gemini-3.5-flash-lite` which has a separate free quota. | Checked JSON output, classification is still accurate. |
| 2026-09-05 | App couldn't find the API key when running `./mvnw`. | I exported the key in one terminal tab but ran the app in another, so the env var got lost. | Moved the API key directly into `application.properties` (added to `.gitignore`). | Much less annoying to run locally now. |
