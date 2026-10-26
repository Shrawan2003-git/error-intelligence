package dev.errorintelligence.incidents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FingerprintV1 {
  public static final short VERSION = 1;
  private static final Pattern UUID =
      Pattern.compile(
          "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
  private static final Pattern TIMESTAMP =
      Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z?\\b");
  private static final Pattern FRAME_LINE = Pattern.compile("\\(([^():]+):\\d+\\)");
  private static final Pattern NUMERIC_PATH_SEGMENT = Pattern.compile("(?<=/)[0-9]{1,18}(?=/|$)");
  private static final Pattern UUID_PATH_SEGMENT = Pattern.compile("(?<=/)[0-9a-fA-F-]{36}(?=/|$)");
  private static final Pattern REQUEST_ID =
      Pattern.compile("(?i)(?:request[-_ ]?id|trace[-_ ]?id)=[^ |]+\\s*");

  public String create(String exceptionType, String stackTrace, String route) {
    String normalizedType = normalize(exceptionType).toLowerCase(Locale.ROOT);
    String frames = normalizeFrames(stackTrace);
    String normalizedRoute = normalizeRoute(route);
    String canonical = normalizedType + "|" + frames + "|" + normalizedRoute;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available.", exception);
    }
  }

  private String normalizeFrames(String stackTrace) {
    if (stackTrace == null || stackTrace.isBlank()) return "";
    return stackTrace
        .lines()
        .map(String::strip)
        .filter(line -> line.startsWith("at "))
        .map(line -> FRAME_LINE.matcher(line).replaceAll("($1)"))
        .map(FingerprintV1::normalize)
        .limit(5)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  private static String normalize(String value) {
    return UUID.matcher(
            TIMESTAMP
                .matcher(REQUEST_ID.matcher(value == null ? "" : value).replaceAll(""))
                .replaceAll("<time>"))
        .replaceAll("<uuid>")
        .replaceAll("\\s+", " ")
        .strip();
  }

  private static String normalizeRoute(String value) {
    if (value == null || value.isBlank()) return "";
    String path = value.strip().split("[?#]", 2)[0];
    path = UUID_PATH_SEGMENT.matcher(path).replaceAll(":id");
    path = NUMERIC_PATH_SEGMENT.matcher(path).replaceAll(":id");
    return path.length() > 2048 ? path.substring(0, 2048) : path;
  }
}
