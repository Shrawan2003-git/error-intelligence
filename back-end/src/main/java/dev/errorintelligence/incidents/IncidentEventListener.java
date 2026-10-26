package dev.errorintelligence.incidents;

import dev.errorintelligence.ingestion.ErrorEventStored;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class IncidentEventListener {
  private final IncidentService incidents;

  public IncidentEventListener(IncidentService incidents) {
    this.incidents = incidents;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void eventStored(ErrorEventStored event) {
    incidents.group(event.eventId());
  }
}
