# Backend — foundation, not production ready

Java **21 LTS**, Spring Boot **4.2.0**, Maven. SQL via Spring JDBC, PostgreSQL JDBC driver. Spring Security denies all non-health routes while business APIs are unimplemented. CSRF remains enabled; the role policy is a pure Java class with unit tests but is **not yet attached to controllers**.

## Run locally

A PostgreSQL database must be available. Supply its credentials through environment variables:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/memodb_preview
export SPRING_DATASOURCE_USERNAME=memo_preview
export SPRING_DATASOURCE_PASSWORD='your-local-preview-password'
cd backend
mvn test
mvn spring-boot:run
curl -f http://127.0.0.1:8080/actuator/health/liveness
curl -f http://127.0.0.1:8080/actuator/health/readiness
```

The database is intentionally **not migrated** or seeded by this bootstrap. Readiness checks connectivity only, not schema compatibility. Do not point at production.

Docker preview: `cp deploy/.env.example deploy/.env.preview`, edit password, then `docker compose --env-file deploy/.env.preview -f deploy/compose.preview.yaml up --build -d`. Exposes the API on 127.0.0.1:8080 only. Frontend and functional API routes have **not** been ported.

No Maven/Docker runtime tests are claimed as passed until executed in a properly provisioned build environment.

## Architecture

The code follows a **modular monolith**: `modules/<bounded-context>/{domain,application,api,infrastructure}` plus a small `sharedkernel/` and runtime `platform/`. See [architecture guide](../docs/ARCHITECTURE.md) and [ADR-0001](../docs/adr/0001-modular-monolith.md).

- `modules/notes/domain`: pure delay classification and note access policy.
- `modules/identity/domain`: privileged operation policies.
- `sharedkernel/identity`: the minimal role vocabulary reused between modules.
- `platform/security`: Spring Security configuration (framework layer).

The ArchUnit test suite enforces separation and no module dependency cycles during `mvn verify`. No actual business HTTP endpoints are implemented yet; the authorization policies are **not wired to controllers**.

## Identity vertical slice (draft PR)

The identity module now includes framework-independent sign-in/session validation,
a password-change use case with conditional PostgreSQL update, optional security
audit events in `updates.audit_log`, and a bounded local failed-attempt limiter.
The legacy `/api/auth` and `/api/admin` login/session routes are partially
ported and CSRF-protected.

**Not production-ready**: the local limiter is not distributed, there is no
real-schema PostgreSQL integration fixture, security audit is best-effort,
and BCrypt/React parity has not yet been verified using the original runtime.
See [identity migration document](../docs/IDENTITY_AUTH_MIGRATION.md).
