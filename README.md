# Standalone Spring Boot + React project

This repository includes the full React/Vite frontend, 16 frontend regression test suites, the cumulative Spring Boot migration code (through phase 58), and a pinned local copy of the 70 original SQL migrations from `waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

## Development

```bash
cd backend && mvn verify
cd ../frontend && npm ci && npm run build && npm test
```

## Fresh Docker preview (nonproduction only)

From the repository root, create `deploy/.env.preview` containing a *disposable* `POSTGRES_PASSWORD=...` value, then:

```bash
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml up --build -d
```

The original migrations `001` through `070` are executed inside **a single SQL transaction**, only by the standard PostgreSQL image's initialization hook on a new empty database volume. This is intentionally NOT a general-purpose migration runner and will not modify an existing database volume. Check `docker compose ... logs postgres` for initialization errors. Existing volumes are never reset automatically. **Never run `down -v` on data you need to retain.**

- UI: http://127.0.0.1:8081
- API health: http://127.0.0.1:8080/actuator/health
- PostgreSQL port is not published.
- Spring Boot and PostgreSQL are restricted to a private Docker bridge network; host HTTP ports bind loopback.

This is a **source-complete preview**, not a production-ready functional replacement. The original `/api/setup/*` UI bootstrap flow and several legacy dashboard and notes APIs are still intentionally denied. The original SQL creates an admin account with a placeholder password hash, so a fresh preview has **no usable administrator login**; do not weaken authentication or seed shared credentials. Setting up a safe initial account provisioning mechanism remains required. Only approved test identities may be used in disposable integration tests. Gitea and Portainer production deployment remain deferred.

See `database/migrations/` for the exact pinned SQL files; CI no longer needs the original repository to replay migrations in the dedicated preview database job. The original repository is unchanged.
