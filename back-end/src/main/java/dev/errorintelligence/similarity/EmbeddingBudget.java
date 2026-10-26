package dev.errorintelligence.similarity;

import dev.errorintelligence.common.api.DomainException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reserve before provider calls; failures consume the reservation to keep retries bounded. */
@Service
public class EmbeddingBudget {
  private final JdbcTemplate jdbc;
  private final int dailyCalls;
  private final long dailyCharacters;

  public EmbeddingBudget(
      JdbcTemplate jdbc,
      @Value("${app.ai.embedding-daily-calls:1000}") int dailyCalls,
      @Value("${app.ai.embedding-daily-characters:1000000}") long dailyCharacters) {
    if (dailyCalls < 1 || dailyCharacters < 12000)
      throw new IllegalArgumentException("Invalid embedding budget.");
    this.jdbc = jdbc;
    this.dailyCalls = dailyCalls;
    this.dailyCharacters = dailyCharacters;
  }

  @Transactional
  public void reserve(UUID organizationId, String input) {
    int length = input.length();
    int reserved =
        jdbc.update(
            "insert into intelligence.embedding_daily_usage(organization_id,usage_day,calls,input_characters) values(?,(now() at time zone 'UTC')::date,1,?) on conflict(organization_id,usage_day) do update set calls=intelligence.embedding_daily_usage.calls+1,input_characters=intelligence.embedding_daily_usage.input_characters+excluded.input_characters where intelligence.embedding_daily_usage.calls<? and intelligence.embedding_daily_usage.input_characters+excluded.input_characters<=?",
            organizationId,
            length,
            dailyCalls,
            dailyCharacters);
    if (reserved == 0)
      throw new DomainException(
          HttpStatus.TOO_MANY_REQUESTS,
          "EMBEDDING_DAILY_LIMIT_REACHED",
          "The organization's daily similarity budget has been reached.");
  }
}
