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

- UI: http://127.0.0.1:8081 (or the port defined by `MEMO_FRONTEND_PORT`)
- API health: http://127.0.0.1:8080/actuator/health
- PostgreSQL port is not published.
- Spring Boot and PostgreSQL are restricted to a private Docker bridge network; host HTTP ports bind loopback.

This is a **source-complete preview**, not a production-ready functional replacement. The original `/api/setup/*` UI bootstrap flow and several legacy dashboard and notes APIs are still intentionally denied. The original SQL creates an admin account with a placeholder password hash, so a fresh preview has **no usable administrator login**; do not weaken authentication or seed shared credentials. Setting up a safe initial account provisioning mechanism remains required. Only approved test identities may be used in disposable integration tests. Gitea and Portainer production deployment remain deferred.

See `database/migrations/` for the exact pinned SQL files; CI no longer needs the original repository to replay migrations in the dedicated preview database job. The original repository is unchanged.

## Windows / Docker Desktop: PostgreSQL initialization

A Windows Git checkout can convert shell scripts to CRLF. The dedicated `deploy/PostgresPreview.Dockerfile` normalizes the init hook to LF **inside the image** and `.gitattributes` asks Git to keep shell scripts in LF. The fresh-database CI job deliberately converts the checked-out hook to CRLF before building the image to prevent regressions.

If an older version failed with `/usr/bin/env: 'bash\\r': No such file or directory`, PostgreSQL may have created its data directory **without applying migrations**. PostgreSQL will not automatically rerun init scripts on that existing volume. Do not delete the volume or run `down -v` as a first response. See [Windows preview database recovery](deploy/WINDOWS_PREVIEW_RECOVERY.md) for guarded, non-destructive recovery instructions. The readiness check only passes when schema version 070 exists.

## Windows: port 8081 is already used or reserved

If Docker Desktop reports `ports are not available` or a Windows socket permissions error for `127.0.0.1:8081`, use an alternate **host** port without modifying the container's port 80 or rebuilding any images. Add this line to the existing `deploy/.env.preview` (keep `POSTGRES_PASSWORD` unchanged):

```dotenv
MEMO_FRONTEND_PORT=8082
```

Then in CMD or PowerShell from the repository root, run:

```cmd
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml up -d --no-build
```

Open http://127.0.0.1:8082. If this port is also unavailable, choose another free host port. Do **not** use `down -v` or regenerate the PostgreSQL password to resolve a frontend port conflict.

## Spring Boot system status (replacement for browser DB setup)

The React Docker image is built with `VITE_MEMO_BACKEND=spring`. In this mode it does not call the .NET-only `/api/setup/*` endpoints or ask users for PostgreSQL credentials. Instead, login starts through `/api/admin/session` and the "حالة النظام" page is available from login or `/?systemStatus=1`. Anonymous visitors can see only a minimal backend availability signal from `GET /api/system/health`. Only a live database-revalidated ADMIN session can read `GET /api/admin/system-status` (database connectivity, schema version 070, migrations). No connection URL, username or password is returned.

Settings remain in Docker/Portainer rather than the browser. The previous React setup wizard is retained exclusively for legacy non-Spring builds. A fresh Spring preview still has **no usable administrator password** until safe initial account provisioning is implemented. Do not interpret a successful status page as authentication or full API compatibility.

After pulling this change, rebuild **only the frontend image and the backend image** to get the new status API and frontend UI. Preserve the PostgreSQL volume and `deploy/.env.preview`.

## First admin login on a fresh preview database

The fresh PostgreSQL 070 seed deliberately has no usable administrator password.
To safely activate its original `admin` user **once** without a browser database
configuration page, see [one-time Windows preview admin bootstrap](deploy/ADMIN_BOOTSTRAP.md).
The command only runs on an unused preview DB, does not expose an HTTP setup
endpoint, and never resets volumes or uses preset credentials.

## Preview dashboard: authorization and health

The original React dashboard calls `GET /api/dashboard/snapshot`. This endpoint
is intentionally disabled by default (`memo.dashboard.snapshot.enabled=false`)
and is enabled **only** by the isolated `deploy/compose.preview.yaml` setting
`MEMO_DASHBOARD_SNAPSHOT_ENABLED=true`. It requires a current database-revalidated
ADMIN session and serves the already-migrated unified snapshot as the flat legacy
React JSON contract. Production must not enable this preview switch until the
remaining parity and release security gates are complete. No unauthenticated or
non-admin access is allowed and no success-shaped placeholder data is returned.

The Spring frontend's dashboard health probe calls `/api/system/health`
instead of the obsolete .NET `/health`, avoiding a misleading offline banner.
This does **not** authorize unsupported dashboard endpoints or bypass any session
checks. A newly initialized preview database may have no notes, so the dashboard
can legitimately display empty metrics.

After pulling updates, rebuild the `memo-api` and `memo-frontend` images and
recreate their containers without deleting the PostgreSQL volume or changing
`deploy/.env.preview`.
