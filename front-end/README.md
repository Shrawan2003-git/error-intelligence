# Front-end

React and TypeScript single-page application for account setup, projects, incident investigation, analytics, and organization administration. Vite proxies API, ingestion, and health requests to the Java back-end during local development. No provider key belongs in this folder or a `VITE_` variable.

Registration opens a separate email verification flow before the first sign-in. Password recovery is available from `/login`. In the local native profile, the backend writes these links to the ignored `.local/mail` directory at the repository root; hosted environments require SMTP. See the [user guide](../docs/user-guide.md).

From this folder:

```powershell
npm ci
npm run dev
```

For the project-local staging service on port 18080, set `$env:API_PROXY_TARGET = 'http://127.0.0.1:18080'` before `npm run dev`. The default proxy target is port 8080. Start the back-end using the [root setup guide](../README.md#running-locally).

Checks:

```powershell
npm run format:check
npm run lint
npm test
npm run build
npm run test:e2e
```

The browser suite runs desktop and mobile viewports. Its interaction test mocks the API; use the [synthetic local demo](../docs/portfolio-demo.md) to exercise real ingestion and investigation without a paid provider call. The source is in `src/`, browser tests are in `e2e/`, and demo capture is in `scripts/`.
