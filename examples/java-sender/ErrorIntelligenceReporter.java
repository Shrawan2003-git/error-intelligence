import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal Java 21 sender for server-side use. Pass only a message you have reviewed as safe.
 * It never sends HTTP request bodies, headers, environment variables, or exception messages.
 */
public final class ErrorIntelligenceReporter {
  private final HttpClient client;
  private final URI endpoint;
  private final String ingestionKey;
  private final String environment;
  private final String release;

  public ErrorIntelligenceReporter(
      URI baseUrl, String ingestionKey, String environment, String release) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    String host = baseUrl.getHost();
    boolean localHttp =
        "http".equals(baseUrl.getScheme())
            && ("localhost".equals(host) || "127.0.0.1".equals(host));
    if (!"https".equals(baseUrl.getScheme()) && !localHttp) {
      throw new IllegalArgumentException("Use HTTPS except on a local loopback server.");
    }
    if (host == null
        || baseUrl.getUserInfo() != null
        || baseUrl.getQuery() != null
        || baseUrl.getFragment() != null
        || (!baseUrl.getPath().isEmpty() && !"/".equals(baseUrl.getPath()))) {
      throw new IllegalArgumentException("baseUrl must be an application origin.");
    }
    this.endpoint = baseUrl.resolve("/ingest/v1/events");
    this.ingestionKey = requireValue(ingestionKey, "ingestionKey");
    this.environment = requireValue(environment, "environment");
    this.release = release == null ? "" : release;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public static ErrorIntelligenceReporter fromEnvironment() {
    return new ErrorIntelligenceReporter(
        URI.create(requireValue(System.getenv("EIP_URL"), "EIP_URL")),
        requireValue(System.getenv("EIP_INGEST_KEY"), "EIP_INGEST_KEY"),
        requireValue(System.getenv("EIP_ENVIRONMENT"), "EIP_ENVIRONMENT"),
        System.getenv("EIP_RELEASE"));
  }

  public CompletableFuture<Boolean> report(Throwable failure, String safeMessage) {
    Objects.requireNonNull(failure, "failure");
    String message = safeMessage == null || safeMessage.isBlank()
        ? "Unhandled application error"
        : safeMessage;
    String body =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"occurredAt\":\""
            + Instant.now()
            + "\",\"environment\":\""
            + json(limit(environment, 80))
            + "\",\"release\":\""
            + json(limit(release, 120))
            + "\",\"exceptionType\":\""
            + json(limit(failure.getClass().getName(), 500))
            + "\",\"message\":\""
            + json(limit(message, 4000))
            + "\",\"stackTrace\":\""
            + json(stackFrames(failure))
            + "\"}";
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .header("X-API-Key", ingestionKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return client
        .sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .thenApply(response -> response.statusCode() == 202)
        .exceptionally(error -> false);
  }

  private static String stackFrames(Throwable failure) {
    StringBuilder frames = new StringBuilder();
    for (StackTraceElement frame : failure.getStackTrace()) {
      if (frames.length() > 11000) break;
      frames.append("at ").append(frame).append('\n');
    }
    return limit(frames.toString(), 12000);
  }

  private static String requireValue(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required.");
    return value;
  }

  private static String limit(String value, int length) {
    return value.length() <= length ? value : value.substring(0, length);
  }

  private static String json(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }
}
