package dev.errorintelligence;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Starts only a newly initialized, disposable cluster inside this project's test directory. */
final class NativePostgres implements AutoCloseable {
  private final Path bin;
  private final Path root;
  private final Path data;
  private final String password = UUID.randomUUID().toString();
  private final int port;
  private boolean running;

  private NativePostgres(String suite) throws IOException {
    String configuredBin = System.getProperty("native.pg.bin", "");
    if (configuredBin.isBlank() || configuredBin.contains("${")) {
      throw new IllegalStateException(
          "Set PG_BIN to the directory containing initdb and pg_ctl for native integration tests.");
    }
    bin = Path.of(configuredBin);
    Path workspace = Path.of(System.getProperty("native.pg.workdir")).toAbsolutePath().normalize();
    root = Files.createTempDirectory(Files.createDirectories(workspace), suite + "-");
    data = root.resolve("data");
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
  }

  static NativePostgres start(String suite) {
    try {
      NativePostgres instance = new NativePostgres(suite);
      Path passwordFile = instance.root.resolve("password.txt");
      Files.writeString(passwordFile, instance.password);
      try {
        instance.command(
            "initdb",
            "-D",
            instance.data.toString(),
            "--username=test_runner",
            "--pwfile=" + passwordFile,
            "--auth-host=scram-sha-256",
            "--auth-local=scram-sha-256",
            "--encoding=UTF8",
            "--locale=C");
      } finally {
        Files.deleteIfExists(passwordFile);
      }
      instance.restart();
      return instance;
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Native PostgreSQL test startup failed. Check PG_BIN and target/native-postgres logs.",
          exception);
    }
  }

  void restart() throws IOException, InterruptedException {
    String options = "-h 127.0.0.1 -p " + port;
    String configuredVectorHome = System.getProperty("native.pgvector.home", "");
    if (!configuredVectorHome.isBlank() && !configuredVectorHome.contains("${")) {
      Path vectorHome = Path.of(configuredVectorHome).toAbsolutePath().normalize();
      options +=
          " -c extension_control_path="
              + vectorHome.resolve("share")
              + ";$system -c dynamic_library_path="
              + vectorHome.resolve("lib")
              + ";$libdir";
    }
    command(
        "pg_ctl",
        "-D",
        data.toString(),
        "-l",
        root.resolve("server.log").toString(),
        "-o",
        options,
        "-w",
        "start");
    running = true;
  }

  void stop() throws IOException, InterruptedException {
    if (running) {
      command("pg_ctl", "-D", data.toString(), "-m", "fast", "-w", "stop");
      running = false;
    }
  }

  String url() {
    return "jdbc:postgresql://127.0.0.1:" + port + "/postgres";
  }

  String username() {
    return "test_runner";
  }

  String password() {
    return password;
  }

  private void command(String executable, String... arguments)
      throws IOException, InterruptedException {
    String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
    List<String> command = new ArrayList<>(List.of(bin.resolve(executable + suffix).toString()));
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("commands.log").toFile()))
            .start();
    if (!process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Native PostgreSQL command timed out: " + executable);
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("Native PostgreSQL command failed: " + executable);
    }
  }

  @Override
  public void close() throws Exception {
    stop();
  }
}
