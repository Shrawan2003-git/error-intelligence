# Error Intelligence — product walkthrough

[Watch the narrated demo](error-intelligence-walkthrough.webm) · [Read the transcript](transcript.md) · [Download captions](captions.vtt)

The video uses a real browser and Java backend with **invented test errors**. It shows the current dark UI, account verification, projects, ingestion keys, grouped incidents, investigation, a verified resolution, analytics, diagnostics, and audit history. The screenshots do not contain a plaintext ingestion key or a real user's data.

## What real problem does it solve?

When a Java service fails repeatedly, engineers often get many separate log lines. It is hard to tell which failures are the same problem, who is working on them, what evidence was found, and whether a fix helped. Error Intelligence turns those reports into a shared investigation. In the video, 25 synthetic checkout failures become three incident groups.

## How does it solve it?

1. **Receive safely.** A service sends a sanitized error event using a key scoped to its project. The backend validates, redacts, and stores it.
2. **Group repeats.** A deterministic fingerprint puts recurring failures into one incident with an occurrence count.
3. **Investigate together.** Engineers see event evidence, status, assignee, comments, and optional diagnostic suggestions in one place.
4. **Keep the verified fix.** A team member records the resolution summary and root cause when the fix is confirmed.
5. **Measure the result.** Analytics shows event volume, open incidents, resolution rate, and frequent failures for the selected project.

The React frontend calls a secured Java 21/Spring Boot API. PostgreSQL stores the work. Organization roles keep project data scoped to the right team; sessions, CSRF checks, rate limits, audit events, and revocable ingestion keys protect access. Redis supports distributed rate limiting outside local native development. AI diagnostics and similarity are optional and need a configured provider.

## How a user tries it

1. Start the local database, backend, and frontend using the [user guide](../user-guide.md).
2. Create an account, verify the email link from `.local/mail` in native development, and sign in.
3. Create a project for the Java service and create its ingestion key. Store the full key server-side.
4. Add a small sender to the Java service, or use the user guide's synthetic test request, to `POST /ingest/v1/events`.
5. Open **Incidents**, inspect the evidence, add a comment or assignee, and record a verified resolution.
6. Open **Analytics** to see the trend. Owners and admins can also review **Team & access** and **Audit log**.

The UI does not automatically collect failures from VS Code or application logs; a service must send events. The local no-cost demo leaves paid AI workers disabled. Hosted email needs SMTP configuration, and public production staging remains a separate release gate.

For a personal portfolio deployment, use the [free VM deployment guide](../deployment.md) and the [Java sender example](../../examples/java-sender/README.md). A live URL still depends on your own VM, hostname, and SMTP account.
