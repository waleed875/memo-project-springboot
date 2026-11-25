# Memo Project — Spring Boot + React

This repository combines the evolving Spring Boot backend with the complete original React/TypeScript/Vite frontend in one clone. Spring sources include the cumulative work of the stacked migration branches through phase 58; the frontend source is pinned to original commit `dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

## Layout

- `backend/`: Java 21 / Spring Boot modular-monolith backend, including notes, identity and dashboard migration work and tests.
- `frontend/`: original React 18 / Vite 5 frontend; build to `frontend/dist`.
- `deploy/`: isolated PostgreSQL/Spring Boot preview and optional UI preview (Docker Compose).
- `docs/` and `.github/workflows/`: migration decisions, compatibility gates and verification.

## Develop locally

```sh
cd backend && mvn verify
cd ../frontend && npm ci && npm run build && npm test
```

Start Spring Boot with an isolated migrated PostgreSQL test database, then run `cd frontend && npm run dev`. The Vite server proxies `/api` requests to localhost:8080.

## Docker preview

Set a non-production password in `deploy/.env.preview` as `POSTGRES_PASSWORD=...`, then run from repository root:

```sh
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml up --build -d
```

Frontend: http://127.0.0.1:8081 | API health: http://127.0.0.1:8080/actuator/health

**Important:** This is not production-ready or functionally equivalent to the old application yet. Legacy public API routes are still blocked by design, database migrations are not automatically applied, and the original setup/login/UI flows have not been validated end-to-end against Spring. Do not migrate users or production traffic yet. GitHub remains the source of truth; Gitea and Portainer deployment are deferred.

The original `waleed875/memo_project` repository is read-only and has not been changed.
