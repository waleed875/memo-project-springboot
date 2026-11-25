# Phase 55 — original DashboardQueryService vs independent JDBC projections

## Source of truth

This stage adds a **new independently executed C# query oracle**, not
a reimplementation of the original SQL. The test-only
`tools/dashboard-full-query-oracle` compiles the original pinned files
`DashboardQueryService.cs`, `DashboardDtos.cs`,
`DashboardContractDtos.cs`, `CompanyMetrics.cs`,
`DashboardContractBuilder.cs`, `MetricsCalculator.cs`,
`DelayCalculator.cs`, and `StatusTranslations.cs` directly from
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.
Only `ConfigurationService` is replaced by a test-only stub that supplies
the disposable PostgreSQL connection string from CI. It is not the
original production configuration service.

## Database and scenarios

The phase-54 GitHub workflow creates a disposable PostgreSQL 16 database
and runs the untouched original SQL migrations 001–070 and fixed seed.
Both C# and Java read this same database. C# runs the **actual**
`DashboardQueryService.GetSnapshotAsync` separately for eight pinned
scenarios: company arrival/writing basis, status selection, arrival-cutoff,
global view, second company, empty period, and previous-period window.

The Java differential test independently invokes
`JdbcDashboardSummaryReader`, `JdbcDashboardDistributionsReader`,
`JdbcDashboardActivityReader`, and `JdbcDashboardFlowReader` with
matching filters. It checks the original SQL result values for:

- Summary scalar counts, duplicate incidents, SLA statistics and delays;
- Company rows, monthly activity, status distributions;
- Duplicate breakdown, duplicate monthly trend, review backlog;
- Incoming/approved time-series, data completeness, recent notes.

The C# snapshot's `serverTime`/`generatedAt` are intentionally not
compared to a Java clock. The date-only `latestArrivalDate` and recent
date strings are excluded from the initial value comparison because they
have intentionally different C#/Java timestamp representations; earlier
Phase 53 covers the shared-wire format separately.
Array sizes and ordered item values are otherwise compared.

This is a scoped gate, **not yet full 28-field snapshot parity**:
top error fields, quality by company, factory and line combined performance,
and the exact projection of timestamps still require paired SQL checks.
The original public `GET /api/dashboard/snapshot` remains blocked.
No original .NET repo changes, main merge, production DB, Docker or Gitea.

## CI semantics

The workflow fails if the original full C# service cannot compile,
does not yield a healthy snapshot, any selected result differs,
or the Java read fails. Unlike a DTO-only test, an original SQL error
blocks this gate. The existing 43-field canonical PostgreSQL analytics
and C#/Java JSON serialization checks are also retained.
