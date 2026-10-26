import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Local contract smoke for the dependency-free sender. It never contacts a real deployment. */
public final class ReporterSmoke {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass an output JSON path.");
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ingest/v1/events",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          Files.writeString(Path.of(args[0]), body);
          int status = "ei_test_only".equals(exchange.getRequestHeaders().getFirst("X-API-Key"))
              ? 202
              : 401;
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    try {
      var reporter =
          new ErrorIntelligenceReporter(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              "ei_test_only",
              "local-smoke",
              "smoke-1");
      boolean accepted =
          reporter.report(new IllegalStateException("private exception message"), "Safe \"checkout\" error")
              .get(5, TimeUnit.SECONDS);
      if (!accepted) throw new IllegalStateException("The local collector did not accept the event.");
    } finally {
      server.stop(0);
    }
  }
}
