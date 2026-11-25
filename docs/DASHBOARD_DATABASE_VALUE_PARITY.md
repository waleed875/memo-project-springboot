# Phase 54 — canonical dashboard metrics over identical original PostgreSQL schema

This is the first dashboard **real database** C# / Java differential gate,
building on the validated Phase 53 JSON serialization gate.

## What is measured

The workflow `dashboard-db-parity-ci.yml` provisions a disposable PostgreSQL
16 database, reads original, pinned migration SQL **001–070** from
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`,
and applies it in migration order. It then seeds reproducible test data
for two companies, one factory/line, ENTRY and EXIT notes, clean/error/
unreviewed/N/A fields, an invalid date, a deleted note, duplicates and
a valid note link.

The executable C# oracle compiles the **original CompanyMetrics.cs** (all
43 fields), invokes the original database function
`reporting.fn_company_metrics_snapshot_v4_filtered` with Npgsql/Dapper
and emits measured results for eight bounded date/filter/company cases.
Java's original JDBC adapter + `DashboardCompanyMetricsService` runs
against the **same database** using identical case values. The test
deep-compares every property, the null or numeric value, and paired
current/previous period results; not just JSON structure.

Original query source is still the authority for the field aliases
and date-basis semantics; both clients intentionally share the
unchanged original PostgreSQL function. This verifies the Java/JDBC
binding, source filters, model mapping, null and decimal behaviour and
previous-period calculations. It **does not independently reimplement
the 43 formulae** — equivalence of SQL function internals is assured by
sharing the original function itself.

## What is NOT yet covered

The original complete C# `DashboardQueryService.GetSnapshotAsync`
contains several other, independent SQL result sets (summary, SLA,
distribution, quality, backlog, duplicates and recent notes). Those are
not yet executed through the complete C# service side-by-side with the
Java aggregate adapters. Full dashboard value parity and React browser
E2E therefore remain open. The real public
`GET /api/dashboard/snapshot` stays forbidden, and this PR stays draft
until every new CI check is green.

The dataset and DB password are **test-only**, and the database is
ephemeral GitHub Actions PostgreSQL. No original repository edits,
main merge, production DB changes, Docker deployment or deferred Gitea.
