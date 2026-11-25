# Phase 58 — real Spring Boot HTTP, PostgreSQL sessions, and Chromium

Runs a real packaged Spring Boot 4 server against a **disposable** PostgreSQL 16
service in GitHub Actions. Replays pinned original SQL migrations 001-070,
seeds nonproduction dashboard notes and two CI-only bcrypt users,
and exercises the *actual* database-backed identity and session services.

The original React DashboardLive is mounted unmodified in a temporary
Vite entrypoint. A CI-only dev proxy maps its original public
`/api/dashboard/snapshot` request to the real Spring internal
`/api/internal/dashboard/snapshot-preview` endpoint and unwraps
the `snapshot` child. Crucially this bridge is **not** a change to Spring
Security or a public API deployment; a direct request to the real Spring
`/api/dashboard/snapshot` must still return HTTP 403 for ADMIN.

Chromium tests exercise CSRF handshakes, missing-CSRF rejection,
BCrypt login and HttpOnly session cookie, real company report JSON,
React chart rendering from the live PostgreSQL database, logout,
non-admin authorization, and DB role revocation / session invalidation.
Runtime user-role changes occur only in the ephemeral database and
are reset after each test. Test credentials are fixture-only.

Limitations: Vite proxy and login bootstrap are temporary test mechanisms;
this does not verify the original full App startup/setup wizard, live
frontend deployment, TLS cookies, or production traffic. The public
dashboard route remains denyAll; PR is draft pending CI.

No original .NET/React repo modifications, main merge, production DB
writes, Docker deployment, or deferred Gitea integration.
