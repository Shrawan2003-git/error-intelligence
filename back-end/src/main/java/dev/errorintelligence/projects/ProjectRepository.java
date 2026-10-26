package dev.errorintelligence.projects;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
  Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Page<Project> findByOrganizationIdAndArchivedAtIsNull(UUID organizationId, Pageable pageable);
}
