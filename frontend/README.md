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
