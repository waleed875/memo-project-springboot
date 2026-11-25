# Memo Dashboard React frontend

This frontend contains the complete original React/TypeScript/Vite implementation pinned to the original repository at commit `dd8eb2ba57864eac2371a3addd0b786ccefa2367`. Original source files are unchanged except for the local Vite build/proxy configuration; the original tests have been copied and adjusted only to resolve paths in this repository. The original repository remains read-only.

Run from a fresh clone:

```sh
cd frontend
npm ci
npm run build
npm test
npm run dev
```

Vite serves `http://127.0.0.1:5173` and proxies `/api` to Spring Boot on `127.0.0.1:8080`. The frontend's original 16 regression test files are kept locally in `frontend/tests/`; `vitest.config.ts` no longer references paths outside this repository.

## Isolated Docker preview

Create `deploy/.env.preview` with a **nonproduction** `POSTGRES_PASSWORD`, then run from the repository root:

```sh
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml up --build -d
```

Frontend: `http://127.0.0.1:8081`. This is a **source-complete integration preview, not a production deployment or working legacy-API replacement**. Spring Boot intentionally blocks some public legacy routes; original setup/login/dashboard UI flows are not yet fully equivalent to the .NET server. A fresh PostgreSQL preview database is not initialized with the original 001–070 SQL migrations automatically. Do not use this against real users, existing databases or production traffic.
