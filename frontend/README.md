# React/Vite frontend

The complete original React frontend is present in this repository. The 16 original regression suites are kept in `frontend/tests` with corrected relative imports.

```bash
cd frontend
npm ci
npm run build
npm test
npm run dev
```

Local Vite proxies `/api` to Spring Boot on 127.0.0.1:8080. The optional Docker preview in `deploy/` exposes the UI at http://127.0.0.1:8081 by default, or at the host port configured using `MEMO_FRONTEND_PORT` in `deploy/.env.preview` (for example `8082` on Windows). On a fresh PostgreSQL preview volume, `database/migrations/001`–`070` are replayed automatically, but original legacy setup and some public API routes remain unsupported, and no default login can be used. This is not production-ready.

## Spring Boot system status mode

The Docker build selects `VITE_MEMO_BACKEND=spring`. Startup skips `/api/setup/status` and checks `/api/admin/session` instead. The database configuration form is replaced with a read-only "حالة النظام" page, also accessible at `/?systemStatus=1`. Anonymous visitors see only public backend health; detailed DB and migration status requires a valid ADMIN session. No passwords or database host settings are exposed. The former setup wizard remains for legacy non-Spring builds. The seed administrator is intentionally not login-ready, so this change does not make first login possible by itself.
