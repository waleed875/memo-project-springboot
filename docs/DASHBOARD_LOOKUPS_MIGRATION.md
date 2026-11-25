# Phase 43 — dashboard lookups HTTP and PostgreSQL parity slice

## Prior CI gate

The Phase 42 CI checks completed successfully: both `verify` and `parity`
runs on commit `654574ee4aa0f86340faf506f0d3143a1e9d0dbe` passed. No main
branch or production deployment has been changed.

## Read-only dashboard slice

`GET /api/dashboard/lookups` is the first implemented dashboard route.
It uses the established DB-revalidated app session and requires current
`ADMIN` role in **both** the application service and the transactional
PostgreSQL reader; stale, unknown, inactive or deleted users fail closed.
The Spring Security filter permits this exact GET path only. Unimplemented
`/api/dashboard/snapshot`, `/company-metrics` and `/review-queue` remain
denyAll.

Contract copied from the pinned original C# `DashboardLookups` model:

```json
{
  "companies": [{"id": "UUID", "name": "Example"}],
  "factories": [{"id": "UUID", "companyId": "UUID", "name": "Example"}],
  "productionLines": [{"id": "UUID", "factoryId": "UUID", "name": "Example"}],
  "noteTypes": [{"id": "UUID", "code": "ENTRY", "nameAr": "نوع"}]
}
```

SQL filter and ordering match `DashboardQueryService.LookupsSql`:
active, nondeleted rows, ordered by company/factory/line name and
Arabic note-type name. Read-only repeatable-read transaction prevents
different catalog snapshots within one response. Return `Cache-Control:
no-store` for sensitive dashboard data. HTTP responses include 401 for no
session, 403 for missing ADMIN role, and 503 for database access errors.
`DashboardLookupsPostgresIT` verifies filtering, foreign keys, sort order,
revoked/disabled roles and database enforcement on PostgreSQL 16.
The separate MockMvc test verifies exact JSON keys, auth and denied routes.

## Dashboard snapshot migration correction

The pinned .NET original `DashboardQueryService.cs` contains
`BuildSnapshotSql(dateBasis)`. It dynamically rewrites date expressions:
`WritingDate` selects `writing_date::date`, whereas `ArrivalDate`
selects `arrival_at::date`. Thus the earlier phase-42 observation based
on the unmodified `SnapshotSql` constant alone was **incomplete**.
The complete SQL and the transformed expressions must be verified before
porting summary counts, SLA or trend metrics.

`DashboardSnapshot` also has nested executive, operations and company
views, company metrics, quality, duplicate trends and comparisons. Do not
return a partial schema from the legacy public `/snapshot` path.

## Remaining work and release gate

- Port the full snapshot DTO / SQL / filter semantics and verify real
  PostgreSQL aggregates against golden fixtures, including writing versus
  arrival date and edge-day inclusivity.
- Port company metrics and review queue with old browser response shape.
- Verify admin login/session and full C# React contract E2E; no deployment
  or main merge until the workflow and parity suite are approved.
- This phase makes no changes to original .NET repository, production DB,
  Docker, Gitea or main branch.
