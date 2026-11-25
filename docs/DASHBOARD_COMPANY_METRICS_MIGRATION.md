# Phase 50 — canonical company analytics and previous-period comparisons

## Source and scope

Pinned original C#:
`MemoDashboard.Web/Backend/Models/CompanyMetrics.cs`,
`Services/DashboardQueryService.cs` at
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

Source-of-truth database function is the **43-column**
`reporting.fn_company_metrics_snapshot_v4_filtered` from original
migration `069_add_loot_and_invoice_note_flags.sql`. The PostgreSQL
test fixture contains the literal original function body, without
reimplementing its formulas in Java. In the deployed application this
function must already exist from the versioned database migrations.

The internal endpoint is
`GET /api/internal/dashboard/company-metrics`.
It is not the original public
`GET /api/dashboard/company-metrics` or `GET /api/dashboard/snapshot`;
both remain denyAll. The response has:
- `companyMetrics`: all 43 original fields and nullable percentages,
  including expected/reviewed/unreviewed/applicable item counts, quality,
  time delays, percentiles, duplicates and ENTRY/EXIT/LOOT compliance.
- `previousCompanyMetrics`: same 43-field model or JSON null.
- `previousPeriod`: from/to/dateBasis/entryCutoffDate or null.
- `effectiveFilters`: the normalized original dashboard filters.

No invented chart or guessed formula appears in the write path.
The Java DTO preserves original field spelling (camelCase JSON) and
BigDecimal for every nullable original C# decimal.

## Period calculations

As in the C# `DashboardQueryService.GetSnapshotAsync`, the previous
period is calculated only when `companyId`, `from` and `to`
are all provided:
- Inclusive current length: `DAYS.between(from,to)+1`
- Previous `to`: current `from - 1 day`
- Previous `from`: previous `to - length + 1 day`
- Previous cutoff: current entryCutoffDate minus that same length
- Keep the same company/factory/line/type/status/dateBasis filters.

Without all three prerequisites, return a null previous period and do
not call the original function a second time. Date range is capped
at 36525 days to bound expensive comparisons; pathological Java date
underflow returns HTTP 400.

## Security and verification

Actor ID comes exclusively from an authenticated session, role ADMIN
is checked in the service, and an active current ADMIN database role
is rechecked inside the same read-only REPEATABLE_READ transaction with
a 30-second timeout. The two analytics calls use one transaction so
the current and previous metrics see the same data snapshot.
HTTP responses use `Cache-Control: no-store` and the same
401/403/400/503 conventions as prior internal dashboard modules.

`DashboardCompanyMetricsPostgresIT` boots PostgreSQL 16 with the
original v4 filtered SQL function (not a mocked Java repository),
and seeded valid/late/clean/error/unreviewed and LOOT notes.
The test checks distinct current and previous windows, nullable
denominators and rates, date basis, previous cutoff, and revocation.
Service unit and MockMvc tests cover period arithmetic, 43-field
JSON contract, no-store, authorization, invalid filters, and the
still-closed public snapshot endpoint.

The test fixture is **not** a full upgrade through migrations 001–070.
Remaining gates: complete legacy DashboardSnapshot composition and
DashboardViewsContract, company analytics API parity, full historic
schema migration, browser E2E, and checked CI on this PR.
No merge to main, no production deployment, no changes to .NET source,
production data, Docker or deferred Gitea.
