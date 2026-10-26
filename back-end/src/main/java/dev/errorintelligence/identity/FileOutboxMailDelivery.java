package dev.errorintelligence.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.auth.mail.mode", havingValue = "file")
final class FileOutboxMailDelivery implements AccountMailDelivery {
  private final Path outbox;

  FileOutboxMailDelivery(
      Environment environment, @Value("${app.auth.mail.outbox:../.local/mail}") String outbox) {
    if (!environment.acceptsProfiles(Profiles.of("native"))
        && !environment.getProperty("app.auth.mail.allow-file", Boolean.class, false))
      throw new IllegalStateException("File mail delivery is allowed only in local development.");
    this.outbox = Path.of(outbox).toAbsolutePath().normalize();
  }

  @Override
  public void send(String recipient, String subject, String body) {
    try {
      Files.createDirectories(outbox);
      Files.writeString(
          outbox.resolve(UUID.randomUUID() + ".txt"),
          "To: " + recipient + "\nSubject: " + subject + "\n\n" + body,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW);
    } catch (IOException error) {
      throw new IllegalStateException("Could not write the local development email outbox.", error);
    }
  }
}
