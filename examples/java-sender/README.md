# Send Java errors to Error Intelligence

[`ErrorIntelligenceReporter.java`](ErrorIntelligenceReporter.java) is a small Java 21 example with no third-party dependency. Copy it into the **server-side** Java application you want to observe. Create a project and ingestion key in Error Intelligence first, then set these environment variables only in that Java service:

| Variable | Example | Purpose |
| --- | --- | --- |
| `EIP_URL` | `https://your-name.duckdns.org` | Public Error Intelligence origin |
| `EIP_INGEST_KEY` | `ei_...` | Project ingestion key; keep it out of Git and browser code |
| `EIP_ENVIRONMENT` | `demo` | Environment label |
| `EIP_RELEASE` | `checkout-1.0` | Optional release label |

In a central exception handler, use it like this:

```java
var reporter = ErrorIntelligenceReporter.fromEnvironment();
try {
  checkoutService.submit();
} catch (Exception failure) {
  reporter.report(failure, "Checkout submission failed");
  throw failure;
}
```

The call is asynchronous and returns `CompletableFuture<Boolean>`. It sends the exception class, a short stack frame list, and **only the safe message you supply**. It does not read the exception message, request bodies, headers, or environment values. An event is accepted when the future completes with `true`. Do not rely on this best-effort example as a durable production queue; the CV demo only needs a simple sender. The backend also validates and redacts incoming data.

For a one-off first test without changing another Java app, use the [PowerShell request in the user guide](../../docs/user-guide.md#5-send-a-first-test-error-to-the-backend).
