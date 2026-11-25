# Memo Dashboard React frontend

The complete original React/TypeScript/Vite sources were copied from `waleed875/memo_project` at commit `dd8eb2ba57864eac2371a3addd0b786ccefa2367`. The original repository is unchanged.

Run the UI locally:

```sh
cd frontend
npm ci
npm run build
npm test
npm run dev
```

Vite serves on localhost:5173 and proxies `/api` to Spring Boot on localhost:8080. To preview both services with Docker, create `deploy/.env.preview` with a fresh disposable `POSTGRES_PASSWORD`, then from repository root:

```sh
docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml -f deploy/compose.frontend-preview.yaml up --build -d
```

The UI is exposed only on localhost:8081. This is a **source-complete integration preview, not a working legacy-API replacement or production deployment**. The React application still invokes legacy public endpoints that Spring Boot intentionally denies; some routes will fail until API parity and acceptance tests are completed. Do not expose this preview to public networks or a production database.
