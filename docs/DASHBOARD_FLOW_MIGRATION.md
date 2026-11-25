# Phase 49 — dashboard arrivals/approvals and completeness

The previous PR #48 originally failed Java test compilation in
`DashboardActivityPostgresIT` because one line contained the literal
backslash+n rather than a newline. Commit `528dbe3d130d78933e5d74a55e535df99f764892`
corrected this on the PR #48 feature branch. Follow-up CI is required;
do not conflate code correction with verified pass.

## Internal-only endpoint

`GET /api/internal/dashboard/flow`

Two components are ported with exact source item field names:
- `incomingVsApproved`: `periodKey`, `periodLabel`, `label`,
  `incoming`, `approved`. The two event series use different
  timestamps: `arrival_at` vs `approved_at`, not the generic
  `dateBasis` selection. A FULL OUTER JOIN combines only observed
  event months, exactly as in the original C# snapshot SQL.
- `dataCompleteness`: the three indicators
  `missing-writing-date`, `missing-factory`,
  `missing-production-line` with the Arabic labels in
  `DashboardQueryService.cs`. For this scoped aggregate, the
  original BuildSnapshotSql date-basis transformation applies.

The `entryCutoffDate` is deliberately based on `created_at`,
matching this exact original C# SQL contract. Other independently
ported dashboard slices may use different cutoff semantics and must
be reconciled before opening the shared public snapshot endpoint.
The original C# company metrics calculation and previous-period
comparison use the database function
`reporting.fn_company_metrics_snapshot_v4_filtered` and require
a separate faithful migration and paired-period verification. This PR
does NOT invent replacement comparison values.

## Security and verification

The route is restricted to ADMIN. Application service checks session
identity and role, the JDBC read transaction checks the currently
active DB role again. The read-only REPEATABLE_READ transaction has a
30-second timeout. Date range is limited to 240 months; SQL filters are
parameterized; no-store response headers.

MockMvc, service unit and PostgreSQL 16 Testcontainers tests cover
role denial, validation, actual event timestamps, missing metadata,
deleted notes, creation-based cutoff and null/empty outcomes.

This is NOT a complete `DashboardSnapshot` implementation; the
public `/api/dashboard/snapshot` remains closed. No original C# repo,
main, production DB, Docker or deferred Gitea deployment changes.
