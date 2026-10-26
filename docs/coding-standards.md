# Coding standards

- Organize Java by business module. Keep controllers, DTOs, mapping, services and persistence responsibilities distinct.
- Use constructor injection, explicit transactions for multi-record operations, immutable response records, and bounded inputs.
- Require tenant context in business services and repository queries. A guessed UUID must never bypass object authorization.
- Flyway migrations are append-only after application to shared environments. Use additive schema evolution and validate clean/upgraded databases.
- Never expose JPA entities or return raw exception messages. Problem codes are stable; human messages avoid sensitive internals.
- Log structured operational events without credentials, tokens or raw user event payloads. Use request correlation IDs.
- Google Java Format is enforced by Spotless. Frontend formatting uses Prettier; ESLint and strict TypeScript checks fail the build.
- React data comes through the centralized client and TanStack Query. Future tenant query keys must include organization/project scope and clear tenant caches on organization switch.
- Positive, negative, authorization and failure-path tests accompany each business slice. Native and container verification scopes are explicitly documented.
- Pin dependencies through reviewed BOMs and lockfiles. Never commit .env, .tools, .local, generated reports or dependency directories.
- Do not add empty domain scaffolding, placeholder endpoints or fabricated business metrics. Implement backlog items as working vertical slices.
- Each change batch states scope, contract/security effects, verification commands and migration/rollback risk. Mark a gate verified only after observing its result.
