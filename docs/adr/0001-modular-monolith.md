# ADR 0001 Modular monolith

Status: accepted by project architecture approval.

## Context

The blueprint requires a modular monolith first, with independently owned domains. There is no measured need for independently deployed services.

## Decision

Use one Spring Boot backend organized by business module, and one React frontend. Keep HTTP, DTO, service and repository boundaries inside each module. Use PostgreSQL transactions for ingestion and grouping. Introduce Kafka or split deployments only after a measured limitation and a reviewed ADR.

## Consequences

Local development and deployment stay manageable. Shared transactions are available. Module dependency discipline and tenant-scoped queries require review and tests as the system grows.
