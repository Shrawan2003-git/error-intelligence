package dev.errorintelligence.similarity;

import dev.errorintelligence.identity.IdentityDtos.Tenant;
import dev.errorintelligence.ingestion.SensitiveDataRedactor;
import dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput;
import dev.errorintelligence.similarity.AnalysisEvidence.EventEvidence;
import dev.errorintelligence.similarity.AnalysisEvidence.ResolutionEvidence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SimilarityService {
  private static final Logger log = LoggerFactory.getLogger(SimilarityService.class);
  private final JdbcTemplate jdbc;
  private final EmbeddingProvider embeddings;
  private final SensitiveDataRedactor redactor;
  private final EmbeddingBudget budget;

  public SimilarityService(
      JdbcTemplate jdbc,
      EmbeddingProvider embeddings,
      SensitiveDataRedactor redactor,
      EmbeddingBudget budget) {
    this.jdbc = jdbc;
    this.embeddings = embeddings;
    this.redactor = redactor;
    this.budget = budget;
  }

  public SimilarityDtos.SearchResult search(Tenant tenant, UUID incidentId) {
    tenant.requireContributor();
    UUID projectId =
        jdbc
            .query(
                "select project_id from intelligence.incident_groups where id=? and organization_id=?",
                (rs, row) -> rs.getObject(1, UUID.class),
                incidentId,
                tenant.organizationId())
            .stream()
            .findFirst()
            .orElseThrow(dev.errorintelligence.common.api.DomainException::missing);
    String text = incidentText(tenant.organizationId(), incidentId);
    budget.reserve(tenant.organizationId(), text);
    float[] vector = embeddings.embed(text);
    String literal = vectorLiteral(vector);
    List<SimilarityDtos.Match> matches =
        jdbc.query(
            "select e.source_id,i.id,e.source_type,i.title,r.summary,r.root_cause,1-(e.embedding <=> ?::vector) from intelligence.incident_embeddings e join intelligence.incident_groups i on i.id=e.incident_group_id and i.organization_id=e.organization_id and i.project_id=e.project_id left join intelligence.incident_resolutions r on e.source_type='RESOLUTION' and r.id=e.source_id and r.incident_group_id=e.incident_group_id where e.organization_id=? and e.project_id=? and e.embedding_model=? and e.incident_group_id<>? and (e.source_type='INCIDENT' or (e.source_type='RESOLUTION' and r.id is not null)) order by e.embedding <=> ?::vector,e.source_id limit 8",
            (rs, row) -> match(rs),
            literal,
            tenant.organizationId(),
            projectId,
            embeddings.model(),
            incidentId,
            literal);
    return new SimilarityDtos.SearchResult(matches, embeddings.model());
  }

  public AnalysisInput enrichForAnalysis(UUID analysisId, AnalysisInput input) {
    try {
      Map<String, UUID> scope =
          jdbc.queryForObject(
              "select organization_id,project_id,incident_group_id from intelligence.incident_ai_analyses where id=?",
              (rs, row) ->
                  Map.of(
                      "organizationId", rs.getObject(1, UUID.class),
                      "projectId", rs.getObject(2, UUID.class),
                      "incidentId", rs.getObject(3, UUID.class)),
              analysisId);
      String text = inputText(input.incidentTitle(), input.events());
      budget.reserve(scope.get("organizationId"), text);
      String vector = vectorLiteral(embeddings.embed(text));
      List<ResolutionEvidence> retrieved =
          jdbc.query(
              "select r.id,r.summary,r.root_cause from intelligence.incident_embeddings e join intelligence.incident_resolutions r on r.id=e.source_id and r.incident_group_id=e.incident_group_id where e.organization_id=? and e.project_id=? and e.embedding_model=? and e.source_type='RESOLUTION' and e.incident_group_id<>? order by e.embedding <=> ?::vector,e.source_id limit 3",
              (rs, row) ->
                  new ResolutionEvidence(
                      rs.getObject(1, UUID.class),
                      redactor.text(rs.getString(2)),
                      redactor.text(rs.getString(3))),
              scope.get("organizationId"),
              scope.get("projectId"),
              embeddings.model(),
              scope.get("incidentId"),
              vector);
      Map<UUID, ResolutionEvidence> merged = new LinkedHashMap<>();
      input.resolutions().forEach(item -> merged.put(item.id(), item));
      retrieved.forEach(item -> merged.putIfAbsent(item.id(), item));
      return new AnalysisInput(input.incidentTitle(), input.events(), List.copyOf(merged.values()));
    } catch (RuntimeException exception) {
      log.info(
          "Verified-resolution retrieval skipped; failureType={}",
          exception.getClass().getSimpleName());
      return input;
    }
  }

  private String incidentText(UUID organizationId, UUID incidentId) {
    List<EventEvidence> events =
        jdbc.query(
            "select e.exception_type,e.message,e.stack_trace,coalesce(e.request_metadata->>'path','') from intelligence.incident_occurrences o join intelligence.error_events e on e.id=o.event_id where o.incident_group_id=? order by o.occurred_at desc,o.event_id desc limit 5",
            (rs, row) ->
                new EventEvidence(
                    null,
                    redactor.text(rs.getString(1)),
                    redactor.text(rs.getString(2)),
                    redactor.text(rs.getString(3)),
                    redactor.text(rs.getString(4))),
            incidentId);
    String title =
        jdbc.queryForObject(
            "select title from intelligence.incident_groups where id=? and organization_id=?",
            String.class,
            incidentId,
            organizationId);
    return inputText(redactor.text(title), events);
  }

  private String inputText(String title, List<EventEvidence> events) {
    StringBuilder text = new StringBuilder(redactor.text(title));
    for (EventEvidence event : events)
      text.append('\n')
          .append(redactor.text(event.exceptionType()))
          .append('\n')
          .append(redactor.text(event.message()))
          .append('\n')
          .append(redactor.text(event.stackTrace()))
          .append('\n')
          .append(redactor.text(event.route()));
    String bounded = text.toString();
    return bounded.length() <= 12000 ? bounded : bounded.substring(0, 12000);
  }

  private SimilarityDtos.Match match(ResultSet rs) throws SQLException {
    return new SimilarityDtos.Match(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getString(3),
        redactor.text(rs.getString(4)),
        rs.getString(5) == null ? null : redactor.text(rs.getString(5)),
        rs.getString(6) == null ? null : redactor.text(rs.getString(6)),
        rs.getDouble(7));
  }

  private String vectorLiteral(float[] values) {
    if (values.length != 1536) throw new IllegalArgumentException("Invalid embedding dimensions.");
    StringBuilder text = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) {
      if (!Float.isFinite(values[i]))
        throw new IllegalArgumentException("Invalid embedding vector.");
      if (i > 0) text.append(',');
      text.append(Float.toString(values[i]));
    }
    return text.append(']').toString();
  }
}
