package dev.errorintelligence.ingestion;

import static dev.errorintelligence.ingestion.IngestionDtos.RequestContext;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SensitiveDataRedactor {
  private static final String MASK = "[REDACTED]";
  private static final Pattern SECRET_ASSIGNMENT =
      Pattern.compile(
          "(?i)(['\"]?\\b[\\w.-]*(?:password|passwd|secret|token|api[_-]?key|authorization|cookie|credential|access[_-]?key)[\\w.-]*['\"]?\\s*[:=]\\s*['\"]?(?:Bearer\\s+)?)([^'\"\\s,;&}]+)['\"]?");
  private static final Pattern SENSITIVE_QUERY =
      Pattern.compile(
          "(?i)([?&][^=&#]*(?:password|passwd|secret|token|api[_-]?key|access[_-]?key)[^=&#]*=)[^&#\\s]*");
  private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
  private static final Pattern EMAIL =
      Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
  private static final Pattern AWS_KEY = Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b");
  private static final Pattern KNOWN_TOKEN =
      Pattern.compile(
          "(?i)\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|sk-(?:proj-)?[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{15,}|AIza[0-9A-Za-z_-]{30,})\\b");
  private static final Pattern PRIVATE_KEY =
      Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");
  private static final Pattern JWT =
      Pattern.compile("\\b[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b");
  private static final Pattern TOKEN_CANDIDATE = Pattern.compile("\\b[A-Za-z0-9_+/=-]{40,}\\b");
  private static final Set<String> SAFE_HEADERS =
      Set.of("content-type", "user-agent", "x-request-id", "x-correlation-id");

  public String text(String value) {
    if (value == null || value.isEmpty()) return "";
    String redacted = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1" + MASK);
    redacted = SENSITIVE_QUERY.matcher(redacted).replaceAll("$1" + MASK);
    redacted = BEARER.matcher(redacted).replaceAll("Bearer " + MASK);
    redacted = EMAIL.matcher(redacted).replaceAll("[REDACTED_EMAIL]");
    redacted = AWS_KEY.matcher(redacted).replaceAll(MASK);
    redacted = KNOWN_TOKEN.matcher(redacted).replaceAll(MASK);
    redacted = PRIVATE_KEY.matcher(redacted).replaceAll(MASK);
    redacted = JWT.matcher(redacted).replaceAll(MASK);
    Matcher candidates = TOKEN_CANDIDATE.matcher(redacted);
    StringBuilder safe = new StringBuilder();
    while (candidates.find()) {
      String candidate = candidates.group();
      candidates.appendReplacement(
          safe, Matcher.quoteReplacement(entropy(candidate) >= 4.2 ? MASK : candidate));
    }
    candidates.appendTail(safe);
    return safe.toString();
  }

  public Map<String, String> environment(Map<String, String> input) {
    return cleanMap(input, false);
  }

  public Map<String, Object> request(RequestContext input) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (input == null) return result;
    if (input.method() != null && !input.method().isBlank())
      result.put("method", input.method().strip().toUpperCase(Locale.ROOT));
    if (input.path() != null && !input.path().isBlank()) {
      String path = input.path().strip();
      if (!path.startsWith("/") || path.startsWith("//")) path = "/";
      String safePath = text(path);
      result.put("path", safePath.substring(0, Math.min(safePath.length(), 2048)));
    }
    Map<String, String> headers = cleanMap(input.headers(), true);
    Map<String, String> query = cleanMap(input.query(), false);
    result.put("headers", headers);
    result.put("query", query);
    result.put("bodyExcerpt", text(input.bodyExcerpt()));
    return result;
  }

  private Map<String, String> cleanMap(Map<String, String> input, boolean headers) {
    Map<String, String> result = new LinkedHashMap<>();
    if (input == null) return result;
    input.forEach(
        (key, value) -> {
          if (key == null || value == null) return;
          String name = key.strip();
          String normalized = name.toLowerCase(Locale.ROOT).replace('-', '_');
          if (headers && !SAFE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) return;
          result.put(name, sensitiveName(normalized) ? MASK : text(value));
        });
    return result;
  }

  private boolean sensitiveName(String name) {
    return name.contains("password")
        || name.contains("passwd")
        || name.contains("secret")
        || name.contains("token")
        || name.contains("api_key")
        || name.equals("key")
        || name.contains("authorization")
        || name.contains("cookie")
        || name.contains("credential")
        || name.contains("email")
        || name.contains("phone")
        || name.contains("ssn")
        || name.contains("private");
  }

  private double entropy(String value) {
    Map<Character, Integer> counts = new LinkedHashMap<>();
    for (char character : value.toCharArray()) counts.merge(character, 1, Integer::sum);
    double result = 0;
    for (int count : counts.values()) {
      double probability = (double) count / value.length();
      result -= probability * (Math.log(probability) / Math.log(2));
    }
    return result;
  }
}
