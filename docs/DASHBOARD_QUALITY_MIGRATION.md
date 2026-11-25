# Phase 46 — Dashboard quality facts and comparisons

## Verified baseline

Phase 45 commit `75b6c5dba716a68c0e937415b5f5c3fcfd6a932f`
passed all four GitHub verify/parity checks.

The source of truth for this slice is pinned C#
`DashboardQueryService.cs` plus migration 060
`reporting.vw_note_indicator_facts_v3`. The exact SQL view is reproduced
in a **test-only** SQL fixture; production uses the existing migrated view.

## New restricted endpoint

`GET /api/internal/dashboard/quality`

ADMIN only, validated current app session and a second live DB role check.
The query is read-only, repeatable-read, 30-second timeout, parameterized.
It accepts the original snapshot date/company/factory/line/note-type/status/
arrival-cutoff filters plus `minSample` (default 10, allowed 1..10000).
No-store response headers. HTTP 401/403/400/503 for authentication, role,
invalid filters and database outage.

It returns:
- `fieldQuality`: field counts and accuracy using applicable reviewed
  values 1..5 as denominator, not unreviewed or N/A.
- `qualityByCompany`: sample, clean/error/eligible/all-N/A note counts,
  eligible note rates, and the company's field quality measurements.
- `qualityByFactory`/`qualityByLine`: measured quality-only fields
  for dimensions, not fabricated timeliness values or zeroed quality.
- `topErrorFields`: up to 10 error-prone fields after minSample threshold.
- `indicatorStateDistribution`: numeric effective values 0..6 with original
  Arabic state labels.
- `effectiveFilters` and `minSample`.

The view defines effective 0 for a missing row or explicit zero, 1 correct,
2..5 error, and 6 not applicable. A clean note requires *no errors, no
unreviewed fields and at least one applicable reviewed field*. Fully N/A
notes are classified separately; notes with errors are eligible even when
other fields are unreviewed.

## Verification

`DashboardQualityPostgresIT` executes the original migration-060 view on
Testcontainers PostgreSQL 16 with clean/error/incomplete/all-N/A/deleted
fixtures. Tests cover denominators, dimensions, dates/status, minSample,
unknown vs missing values, empty results and role revocation.
MockMvc and application tests exercise session and role access, validation,
JSON and keeping public `/api/dashboard/snapshot` denied.

This is an **internal subset**, not full legacy snapshot compatibility.
Outstanding tasks include integrating time and quality into full legacy
PerformanceItem, full executive/operations/company view compositions,
duplicate trends and recent notes, company metrics, browser end-to-end
checks, and historic database migration verification.

No changes to original .NET repository, main branch, production DB,
Docker deployment or deferred Gitea build flow.
