# Security reporting

This project is a locally developed MVP; no public security contact or supported production release is configured yet. Before publication, the repository owner must configure a private vulnerability-reporting channel and document its response expectations.

Report suspected vulnerabilities privately to the repository owner through an already established private channel. Do not post credentials, tenant data, exploit payloads or raw stack traces in public issues. Include affected revision, minimal synthetic reproduction, impact and suggested mitigation.

## Engineering controls

Keep secrets outside Git. Use separate deployment credentials, short-lived access tokens, restricted network access and per-environment signing keys. Never disable tenant checks, CSRF, refresh-token replay detection or fail-closed production rate limiting to bypass a failing test.

New accounts require email verification. Configure `PUBLIC_APP_URL` as the deployed HTTPS frontend origin and use a trusted SMTP sender. Verification and password reset links are single-use, short-lived, and backed by digest-only challenges; pending mail bodies are encrypted in the database queue. Keep the native file outbox under the ignored `.local/mail` directory, restrict its local access, and never publish its contents.

Dependency scans are dated evidence, not a guarantee. Triage findings against the deployed artifact and record remediation or a reviewed exception with owner and expiry. No blanket suppression of unfixed vulnerabilities is permitted by the provided CI gate.

## Suspected compromise

Disable affected credentials and worker access, preserve restricted evidence, identify the affected tenants and version, and coordinate recovery with the owner. Signing-key rotation invalidates access tokens; revoke affected persisted sessions and ingestion keys as appropriate. Test recovery with synthetic data before reopening traffic.
