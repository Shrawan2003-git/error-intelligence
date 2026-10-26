# Review implementation completion record — 18 October 2026

Scope: local implementation and verification of the engineering review. The owner explicitly deferred remote repository publication and independent staging, and required other Docker projects/databases to remain untouched. No paid provider calls were made.

## Changes completed in this follow-up

- Ran the real PostgreSQL/Redis container test path; the final patched Java build passed 19 unit tests and 16 integration tests with no failures/errors/skips.
- Expanded the shared migration contract with a V7 tenant/project/environment/event/incident/embedding-job graph. V8–V10 preserve its data and create the deduplication receipt; rerunning migrations performs no work. The original probe test remains.
- Reproduced a real login-rate-limit bypass through forged forwarding headers. The edge Nginx proxy now replaces incoming `X-Forwarded-For` with the connection address. Added a regression that sends changing spoofed headers and must reach the IP limit. This is the documented single-edge topology; additional load balancers require a separately verified trust boundary.
- Split authentication form rendering into `front-end/src/features/auth/AuthForm.tsx` and API data contracts into `front-end/src/api/contracts.ts`. Existing imports remain compatible. This is incremental decomposition; broader repository/component extraction remains ongoing maintainability work.
- Added executable Prometheus alert tests for low-volume failures, zero traffic, scrape failure/recovery, stale collection and old work. Added the gate to CI.
- Added Java package-inventory/advisory and runtime-image scans to CI, with retained JSON reports and immutable image IDs. Java advisories have an independent job so security failures do not suppress functional acceptance. Empty Java inventory is a failure. No advisory suppressions were added.
- Added weekly dependency-update configuration for Maven, npm, Actions and Docker. It becomes active only when published to a configured GitHub repository.
- Upgraded the frontend runtime to Nginx 1.30.5. Its high/critical OS findings fell from 43 to zero (70 detected packages). Updated Redis TLS packages within Redis 7.4.11; its two findings fell to zero (19 detected packages).
- Patched libexpat in both Alpine application images after refreshed advisory data exposed CVE-2026-77214. Runtime users remain unprivileged (`101` for Nginx and `app` for Java).
- Added a PostgreSQL image build that applies available Debian 12 package updates while retaining PostgreSQL 17 and pgvector 0.8.7. This does not migrate or replace other projects' databases.
- Applied compatible dependency overrides: Jackson 2.21.7, Netty 4.1.137.Final, Tomcat 10.1.61 and PostgreSQL JDBC 42.7.12. Tomcat 10.1.58, initially suggested by the advisory database, was unpublished; Maven Central was checked before choosing 10.1.61.
- Added CONTRIBUTING.md, SECURITY.md, a release-readiness checklist and a source/artifact evidence recorder. Corrected the runbook's stale retention interval and documented the bounded cleanup behavior.

## Verification record

- Final patched backend: 19 unit tests and 16 container integration tests passed, zero failures/errors/skips.
- Frontend: 14 tests, formatting, lint and production build passed after the form/type extraction; final format/lint checks also passed.
- Final patched Compose stack: all four services became healthy. All eight desktop/mobile Playwright checks passed (four smoke checks, two live API workflows and two forwarding-header regressions). The desktop spoof regression independently reaches the limit; the mobile check also sees the same already-limited IP bucket.
- Promtool alert fixtures passed; CI, dependency-update and Compose YAML parsed without duplicate keys.
- Native PostgreSQL backup/restore and load evidence from the prior implementation remains historical; those native checks were not rerun after these dependency overrides. The final patched build was verified through the container path.
- A final CycloneDX dependency inventory, JAR checksum and source-file manifest accompany the advisory reports. No remote Actions run is claimed.

Evidence lives in ignored `.local/release-evidence`, `.local/review-security-backend-final.log`, `.local/review-upgrade-tests.log`, and the frontend Playwright reports. A source manifest is used because this repository has no HEAD commit; it is not a substitute for signed/revision-linked remote release evidence.

## Security assessment and release gate

The scan threshold is HIGH/CRITICAL. Findings are package/advisory pairs, not distinct exploitable application paths. A zero count at this threshold does not claim the absence of lower-severity or unknown vulnerabilities.

| Scan | Detected packages | Remaining high/critical findings |
| --- | ---: | ---: |
| Patched Java artifact | 127 | 2 |
| Backend OS | 73 | 0 |
| Frontend OS | 70 | 0 |
| Redis OS | 19 | 0 |
| PostgreSQL OS | 144 | 74 |

npm audit also reported zero known vulnerabilities across runtime and development dependencies. Reports retain their actual execution times; this is dated evidence.

The original Java artifact had 13 findings across 127 packages; compatible patches removed 11. Two Spring MVC advisories require assessment:

- [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) requires XSLT view rendering with an implicit view name and a catch-all mapping. Source inspection found no XsltView configuration or this rendering path. Spring rates it medium, while the scanner's source rates it critical.
- [CVE-2026-47890](https://spring.io/security/cve-2026-47890/) requires SSE view fragments containing attacker-controlled content. Source inspection found no SSE/fragment rendering feature. Spring rates it low, while the scanner's source rates it critical.

These are applicability assessments, not approved security exceptions. The affected library remains installed and the CI gate remains red until a reviewed exception or supported upgrade resolves it. Spring lists the 6.2.20 fixes as enterprise-only and 7.0.9 as the OSS fix. An OSS framework upgrade requires a coordinated Boot/framework migration, rather than mixing Spring 7 into Boot 3.5. No commercial access or license was assumed.

PostgreSQL's original image had 103 package/advisory findings. Available Debian updates removed 29; the remaining 74 have no fixed version listed for this distribution in the scanned database. Selecting an alternative supported base or obtaining reviewed applicability decisions remains release work; it is not hidden by a suppression. Do not present this as a production security sign-off or bypass the gate with `--ignore-unfixed`.

## What is still outside completion

Remote publication/CI and independent HTTPS staging were explicitly deferred. Before real users: resolve remaining dependency/distribution findings, verify the actual trusted proxy and least-privilege database roles, configure private security reporting, approve lifecycle policies and provider budgets, prove off-machine recovery objectives and alert delivery, perform representative sustained capacity/retrieval evaluation, and decide account recovery/invitations. See [release-readiness.md](release-readiness.md).

The broad review is implemented as a local hardening pass, not a guarantee that every future feature or production criterion is complete. Further component decomposition, generated schema/type alignment and richer permission-refresh UX can be pursued incrementally; the tested correctness and isolation fixes remain in place.

## Evidence reconciliation — 20 October 2026

All 237 source files in the saved manifest and the packaged JAR checksum matched the verified evidence before this documentation update. The saved final scans confirm the counts above. Replaced incomplete PostgreSQL/Redis image records and the stale combined image list with records derived from each final scan, explicitly identifying image configuration IDs rather than registry manifest digests. Scan and test dates remain 18 October; no new scan or test execution is claimed.

Docker's engine was unavailable during this read-only follow-up, so current container status could not be checked. Docker was not started or reconfigured. The isolation and shutdown observations below describe the earlier completed verification.

## Session race fix — 20 October 2026

Delayed login, registration and organization-switch responses can no longer restore local account state after logout or supersede a newer session operation. An old request's 401 response cannot refresh or clear a newer login. Logout clears the in-memory token and account immediately, even while the remote request is pending. IdentityWorkspace now obtains account changes from the session listener instead of applying duplicate async completion updates.

Five new regression cases exercise these response orderings. All 19 frontend tests, formatting, lint, TypeScript checking and the production build passed. The initial sandbox test run failed on missing temporary transform files; the run outside the sandbox passed. This change was validated with frontend tests; the 18 October browser/container results and advisory scans remain historical and do not cover the rebuilt frontend. No Docker commands or database operations were used for this fix. The existing Web Locks compatibility limitation remains unchanged.

The new source manifest and frontend build checksums are recorded separately under `.local/release-evidence/session-races-2026-10-20` to preserve the prior release evidence. The security release blockers above remain unresolved.

## Docker isolation and cleanup

Only Compose project `eip-review-20261017`, its dedicated test volume/network, disposable Testcontainers resources, and explicitly named `eip-review-*` scanner resources were used. Ports: 55439, 56389, 18089 and 55179, all loopback. No Docker prune, `down -v`, global reset, or operations on another project's database were run. The review stack and its network were stopped/removed after acceptance. Its dedicated PostgreSQL volume and advisory-cache volume are retained. The previously running aquanova API, UI and database containers were verified still running. Review images and ignored JSON/SBOM/checksum evidence are retained; no existing project volume was deleted.




