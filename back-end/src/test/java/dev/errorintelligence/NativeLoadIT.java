package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.errorintelligence.common.security.Credentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Measures warmed loopback HTTP ingestion with eight clients and mixed fingerprints. */
@ActiveProfiles("native")
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {"app.ai.worker.enabled=false", "app.ai.embedding-worker.enabled=false"})
class NativeLoadIT {
  private static final NativePostgres POSTGRES = NativePostgres.start("load");
  private static final UUID ORGANIZATION = UUID.randomUUID();
  private static final UUID PROJECT = UUID.randomUUID();
  private static final String API_KEY = "ei_" + Credentials.randomToken();

  @Autowired JdbcTemplate jdbc;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::url);
    registry.add("spring.datasource.username", POSTGRES::username);
    registry.add("spring.datasource.password", POSTGRES::password);
  }

  @AfterAll
  static void shutdown() throws Exception {
    POSTGRES.close();
  }

  @Test
  void ingestionP95StaysBelowThreeHundredMillisecondsAtEightConcurrentClients() throws Exception {
    jdbc.update(
        "insert into intelligence.organizations(id,name) values(?,?)", ORGANIZATION, "load-test");
    jdbc.update(
        "insert into intelligence.projects(id,organization_id,name,slug) values(?,?,?,?)",
        PROJECT,
        ORGANIZATION,
        "Load test",
        "load-test");
    jdbc.update(
        "insert into intelligence.ingestion_keys(id,project_id,label,digest,prefix,scope) values(?,?,?,?,?,'INGEST')",
        UUID.randomUUID(),
        PROJECT,
        "native-load-test",
        Credentials.digest(API_KEY),
        API_KEY.substring(0, 16));

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    List<CompletableFuture<Long>> requests = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(8)) {
      // Warm the HTTP connector, JDBC pool, and representative incident rows before timing.
      List<CompletableFuture<Void>> warmups = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        int eventNumber = 1000 + index;
        warmups.add(CompletableFuture.runAsync(() -> postEvent(client, eventNumber), executor));
      }
      CompletableFuture.allOf(warmups.toArray(CompletableFuture[]::new)).join();

      long began = System.nanoTime();
      for (int index = 0; index < 120; index++) {
        int eventNumber = index;
        requests.add(CompletableFuture.supplyAsync(() -> postEvent(client, eventNumber), executor));
      }
      List<Long> samples = requests.stream().map(CompletableFuture::join).toList();
      List<Long> sorted = new ArrayList<>(samples);
      Collections.sort(sorted);
      long p50 = sorted.get((int) Math.ceil(sorted.size() * 0.50) - 1);
      long p90 = sorted.get((int) Math.ceil(sorted.size() * 0.90) - 1);
      long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
      long p99 = sorted.get((int) Math.ceil(sorted.size() * 0.99) - 1);
      long maximum = sorted.getLast();
      double throughput =
          samples.size() / (Duration.ofNanos(System.nanoTime() - began).toMillis() / 1000.0);
      System.out.printf(
          "Native ingestion steady-state load: requests=%d concurrentClients=8 fingerprints=20 warmups=8 p50=%dms p90=%dms p95=%dms p99=%dms max=%dms throughput=%.1f/s%n",
          samples.size(), p50, p90, p95, p99, maximum, throughput);
      assertThat(p95).isLessThan(300);
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.error_events where project_id=?",
                Long.class,
                PROJECT))
        .isEqualTo(128);
  }

  private long postEvent(HttpClient client, int eventNumber) {
    String body =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"occurredAt\":\""
            + Instant.now()
            + "\",\"environment\":\"load-test\",\"exceptionType\":\"com.example.LoadFailure"
            + (eventNumber % 20)
            + "\",\"message\":\"Load event "
            + eventNumber
            + "\",\"stackTrace\":\" at com.example.Load.run(Load.java:42)\"}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ingest/v1/events"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-API-Key", API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    long start = System.nanoTime();
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(202);
      return Duration.ofNanos(System.nanoTime() - start).toMillis();
    } catch (Exception exception) {
      throw new IllegalStateException("Loopback ingestion request failed.", exception);
    }
  }
}
