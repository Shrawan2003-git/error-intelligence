# Phase 5: Similarity and retrieval-augmented diagnostics

Phase 5 follows the blueprint's pgvector, embeddings, verified-resolution retrieval, and similar-incident requirements. The implementation will index only sanitized incident evidence and human-verified resolutions. Every retrieval query will bind organization and project scope before calculating similarity, and retrieved resolutions will remain evidence for the Phase 4 diagnostic prompt rather than executable instructions.

## Planned delivery

- Add a versioned embedding adapter and bounded asynchronous indexing for verified resolutions and incident summaries.
- Store model/version metadata, source identifiers, tenant/project scope, and vector data in PostgreSQL with pgvector.
- Add tenant-scoped similar-incident and verified-resolution retrieval APIs and connect selected results to AI diagnostics.
- Verify retrieval with a deterministic quality fixture set, including cross-tenant exclusion, no unverified resolution leakage, and expected nearest neighbors.

## Local prerequisite

The configured PostgreSQL 18 installation currently lacks the pgvector extension (`vector.control` and `vector.dll` were absent). The official no-Docker Windows build uses the matching PostgreSQL headers, Visual Studio C++ build tools, and `nmake`: [pgvector Windows installation](https://github.com/pgvector/pgvector#installation). pgvector v0.8.7 now compiles locally against PostgreSQL 18. PostgreSQL 18 supports `extension_control_path` and `dynamic_library_path`, so the native test runner can load the project-local runtime without copying files into `Program Files` or installing Docker.

The project runner places the compiled runtime in the user's temporary directory and configures only disposable native test clusters to load it. The test gate verifies `CREATE EXTENSION vector` and cosine distance before exercising application migrations. The normal development PostgreSQL service must have pgvector installed or its extension paths configured before applying the Phase 5 migration.
