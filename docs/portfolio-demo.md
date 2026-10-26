# Portfolio demo (no Docker or paid API calls)

This is a local, synthetic demo for showing the implemented product. It does not publish the app or establish the independent production staging gate. The staging script uses only the project's PostgreSQL data directory, its dedicated Redis process, and loopback ports 15433, 16379, and 18080. It does not use Docker Desktop. AI workers stay disabled, so the capture makes no OpenAI API calls.

## Start and capture

From the repository root in PowerShell, start the isolated staging services:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Local-Staging.ps1 -Action Start
```

In another terminal, start the frontend with its proxy aimed at local staging:

```powershell
cd front-end
npm ci
$env:API_PROXY_TARGET = 'http://127.0.0.1:18080'
npm run dev
```

In a third terminal, from `front-end`, seed a **new synthetic account/project**, ingest 25 fake events, verify one resolution, and capture five browser screenshots:

```powershell
npm run demo:capture -- --acknowledge-synthetic-writes
```

The script refuses to seed a backend that is not the Redis-required local staging profile. It never prints the generated ingestion key or password. The disposable sign-in details are saved to ignored `.local/portfolio-demo-access.txt` at the repository root. Open `http://127.0.0.1:5173` and sign in using that file to walk through analytics, incidents, comments, keys, and organization controls. Run the capture again to create a new demo account; older synthetic rows remain in the isolated database.

Stop the frontend with Ctrl+C. Then stop only the project staging services:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Local-Staging.ps1 -Action Stop
```

`Stop` preserves the local staging data and credentials. It does not touch any company-project containers or volumes.

## Shareable evidence

- [Incident investigation](screenshots/portfolio-incidents.png): three grouped failures and a human-verified resolution.
- [Project analytics](screenshots/portfolio-analytics.png): 25 synthetic events, incident statuses, and daily volume.
- [Desktop workspace](screenshots/portfolio-workspace.png) and [mobile workspace](screenshots/portfolio-mobile.png).
- [Mobile incident view](screenshots/portfolio-mobile-incidents.png).
- [Phase 8 load report](phase-8-verification.md): measured local P95 and throughput, with explicit limits.

These screenshots use only `example.test` identities and invented checkout failures. They show the real browser and backend, but AI inference results are absent because paid workers were disabled. The application is locally rehearsed, not publicly deployed. Before a CV or GitHub release, use the exact claims in [the README](../README.md) and avoid describing the independent production staging gate as complete.
