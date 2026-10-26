# Identity and tenancy design

Phase 1 is the blueprint's identity/tenancy delivery phase. This document records the original Phase 1 design; the current registration, email verification, and password recovery contract is in [API overview](api.md). Accounts are global; each signup creates an organization and an OWNER membership in one transaction. Accounts can create additional organizations and select an existing membership. At Phase 1, no email delivery or account-recovery provider was configured. Administrative member enrollment therefore targeted an existing registered account without claiming email ownership verification.

## Data and constraints

`users`: UUID PK, normalized unique email (254), display name (100), BCrypt password hash, created_at. `organizations`: UUID PK, name (120), created_at. `memberships`: composite organization/user PK with FKs, constrained OWNER/ADMIN/DEVELOPER/VIEWER role, created_at. Organization locking serializes membership edits; only OWNER grants/removes owner privileges; the last owner cannot leave or be demoted.

`sessions`: UUID PK, user FK, active organization FK, expiry, revoked_at, created_at. `refresh_tokens`: UUID PK, session FK, unique SHA256 digest, expiry, consumed_at. Session locking serializes rotations; reuse revokes the entire session, including existing access JWTs. Access tokens contain user, session and organization identifiers only; live session and membership are checked on every request. Organization switching invalidates the old access context and returns a new access token. Refresh cookies are HttpOnly, SameSite Strict, Secure outside the loopback native profile, path /api/v1/auth. CSRF cookie and request header are required on unsafe browser APIs including login. Access tokens remain in memory.

`projects`: UUID PK, organization FK, unique organization/slug, name, created_at, archived_at. `environments`: UUID PK, project FK, unique project/name, created_at. `releases`: UUID PK, project FK, unique project/version, created_at. `ingestion_keys`: UUID PK, project FK, label, unique SHA256 digest, safe prefix, scope INGEST, created_at, revoked_at. Generated credentials contain 256 random bits and are returned once. Project deletion is an archive operation so subsequent incident history remains attributable.

`audit_events`: UUID PK, organization FK, optional actor user FK, action, target UUID, created_at. Audit content contains identifiers and action names, never passwords, tokens, keys, emails or payloads.

All business timestamps use timestamptz. Queries for tenant resources bind organization IDs obtained from authenticated session context. Nonmember object IDs return 404. OWNER/ADMIN manage projects and keys; all memberships read; DEVELOPER and VIEWER cannot administer resources. Pages are bounded to 100 entries. Redis supplies atomic, expiring distributed rate-limit counters in production; dependency failure rejects auth traffic safely with 503. The loopback-only `native` profile uses an in-process one-minute counter map with a 10,000-bucket cap, suitable for one local process and never selected by production packaging. It requires no Docker or WSL Redis installation.

## API contract

| Method | Route | Access | Result |
| --- | --- | --- | --- |
| GET | /api/v1/auth/csrf | Public | CSRF token/header name; no-store |
| POST | /api/v1/auth/register | Public + CSRF, rate limited | 201 access token/account, refresh cookie |
| POST | /api/v1/auth/login | Public + CSRF, rate limited | 200 access token/account, refresh cookie |
| POST | /api/v1/auth/refresh | Refresh cookie + CSRF | Rotate token or revoke reused session |
| POST | /api/v1/auth/logout | CSRF | Revoke session; clear refresh cookie; 204 |
| GET | /api/v1/auth/me | Bearer | Current account and organization memberships |
| POST | /api/v1/auth/organization | Bearer + CSRF | Switch to membership; return new access token |
| POST | /api/v1/organizations | Bearer + CSRF | Create owned organization; 201 |
| GET/POST | /api/v1/organizations/{id}/members | Current organization; admin writes | Member list/enrollment |
| PATCH/DELETE | /api/v1/organizations/{id}/members/{userId} | Current organization; privilege checks | Role change/removal |
| GET/POST | /api/v1/projects | Member reads/admin writes | Paginated list/create |
| GET/PATCH/DELETE | /api/v1/projects/{id} | Member reads/admin writes | Detail/update/archive |
| GET/POST | /api/v1/projects/{id}/environments | Member reads/admin writes | List/create |
| GET/POST | /api/v1/projects/{id}/releases | Member reads/admin writes | List/create |
| GET/POST | /api/v1/projects/{id}/keys | Admin | Safe key metadata/create once |
| DELETE | /api/v1/projects/{id}/keys/{keyId} | Admin | Revoke; 204 |

Errors use Problem Details with safe code and traceId: 400 validation, 401 invalid/expired session, 403 privilege/CSRF denial, 404 scoped resource absence, 409 uniqueness/last-owner rule, 429 rate limit, 503 dependency unavailable. No passwords, JWTs or key material are logged.

References: [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html), [Redis Windows/WSL setup](https://redis.io/docs/latest/operate/oss_and_stack/install/archive/install-redis/install-redis-on-windows/).
