# Phase 4: AI diagnostics

Phase 4 adds evidence-linked diagnostic suggestions to tenant-scoped incidents. Analysis requests create durable PostgreSQL jobs, which the application worker claims with `FOR UPDATE SKIP LOCKED`. The OpenAI adapter uses strict structured output, no tools, bounded request timeouts, and `store: false`. The API key is read only from the server-side `OPENAI_API_KEY` environment variable.

Before model input is assembled, stored event fields and verified resolution excerpts are bounded and redacted again. Model output is schema-validated, evidence excerpts must match sanitized input, references must point to known verified resolutions, and command-like or unsafe suggestions are rejected. Accepted model text is redacted again before persistence. Provider failures retry a bounded number of times and then store a deterministic `UNKNOWN` result for manual investigation.

Requests are limited to incident contributors and rate-limited per organization. Analysis rows carry tenant/project scope, prompt version, model, status, token counts, and non-sensitive failure codes. The frontend renders evidence, hypotheses, safe investigation steps, and limitations; it never runs generated actions.

Phase 7 adds a configurable daily per-organization analysis cap (`AI_DAILY_ANALYSIS_LIMIT`, default 100) enforced under an organization row lock, alongside the existing five-per-minute limit and bounded provider timeout/retries. An active duplicate returns its existing job without consuming another daily slot. This bounds analysis request volume; operators must also set a provider-side monetary budget before enabling paid AI in staging or production.

## Boundaries

- No API key is committed or sent to the browser.
- No raw event secrets are sent to the model.
- The provider has no shell, code-editing, or other tools.
- The feature degrades to an explicit fallback when credentials or provider output are unavailable.
- The integration tests use a stub provider. A live provider call requires a locally configured key and is not part of the verification gate.
