package dev.errorintelligence.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class EventRetentionJobTest {
  @Test
  void zeroRetentionWindowDisablesDeletionWithoutQueryingDatabase() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var job = new EventRetentionJob(jdbc, new SimpleMeterRegistry(), 0);

    assertThat(job.purgeExpiredBatch()).isZero();
    verifyNoInteractions(jdbc);
  }
}
