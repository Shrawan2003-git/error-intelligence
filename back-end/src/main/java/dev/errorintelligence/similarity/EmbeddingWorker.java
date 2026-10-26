package dev.errorintelligence.similarity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.ai.embedding-worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class EmbeddingWorker {
  private static final Logger log = LoggerFactory.getLogger(EmbeddingWorker.class);
  private final EmbeddingJobQueue queue;
  private final EmbeddingProvider provider;
  private final EmbeddingBudget budget;

  public EmbeddingWorker(
      EmbeddingJobQueue queue, EmbeddingProvider provider, EmbeddingBudget budget) {
    this.queue = queue;
    this.provider = provider;
    this.budget = budget;
  }

  @Scheduled(fixedDelayString = "${app.ai.embedding-poll-interval:PT2S}")
  public void poll() {
    EmbeddingJobQueue.Job job;
    try {
      job = queue.claimNext();
    } catch (RuntimeException exception) {
      log.warn("Embedding queue poll failed; failureType={}", exception.getClass().getSimpleName());
      return;
    }
    if (job == null) return;
    try {
      budget.reserve(job.organizationId(), job.sourceText());
      queue.complete(job, provider.embed(job.sourceText()), provider.model());
    } catch (RuntimeException exception) {
      log.warn("Embedding job failed; failureType={}", exception.getClass().getSimpleName());
      queue.failed(job, "EMBEDDING_PROVIDER_FAILURE");
    }
  }
}
