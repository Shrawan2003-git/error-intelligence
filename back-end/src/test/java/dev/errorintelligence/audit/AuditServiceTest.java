package dev.errorintelligence.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.identity.IdentityDtos.Role;
import dev.errorintelligence.identity.IdentityDtos.Tenant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditServiceTest {
  @Test
  void viewersCannotReadAuditTrail() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var service = new AuditService(jdbc);

    assertThatThrownBy(
            () ->
                service.recent(
                    new Tenant(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Role.VIEWER),
                    50))
        .isInstanceOf(DomainException.class);
    verifyNoInteractions(jdbc);
  }
}
