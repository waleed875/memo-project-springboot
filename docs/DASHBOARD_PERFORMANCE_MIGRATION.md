# Phase 47 — unified dashboard performance projections

The Phase 46 verification checks passed on latest runs. An earlier parity
run for the same commit failed solely on Maven Central HTTP 429 during
parent-POM retrieval; the subsequent run passed. This does not imply that
all historical datasets or full browser behavior have been proven.

## Endpoint and contract

`GET /api/internal/dashboard/performance`, ADMIN only, no-store response,
current session + live DB ADMIN revalidation, read-only REPEATABLE_READ TX.

Response has three arrays:
- `timelinessByCompany`
- `performanceByFactory`
- `performanceByLine`

Each array uses the original C# `PerformanceItem` scalar field names:
`dimensionId`, `label`, `totalNotes`, `eligibleCount`,
`lateCount`, `onTimeCount`, `sampleCount`, `correctCount`,
`errorCount`, `qualityEligibleCount`, `lateRate`, `accuracyRate`.

The same SQL statement combines the **scoped notes** and a
`per_note_quality` aggregate from `reporting.vw_note_indicator_facts_v3`:
- Late: writing and arrival dates valid, delay strictly >5 days.
- Clean quality: zero errors, zero unreviewed and at least one applicable
  reviewed indicator.
- Error quality: any error indicator, regardless of additional unreviewed
  indicators.
- Notes with no applicable facts or only N/A do not enter the clean/error
  denominator, and null rates are retained when there is no denominator.
- `lateRate = lateCount / eligibleCount × 100`.
- `accuracyRate = correctCount / qualityEligibleCount × 100`.
- Both round to 2 decimal places, with C# midpoint-away-from-zero semantics.

Current active company rows remain visible at zero counts, while factories
and lines with no scoped notes are excluded, and only the top 10 are
returned. Fields come from one SQL result and one DB snapshot rather than
joining independent HTTP responses.

The previously ported date-basis/status/dimension/cutoff filters are shared;
the original public `GET /api/dashboard/snapshot` remains denyAll.
The internal endpoint is explicitly **not** the complete DashboardSnapshot
contract.

## Tests and boundaries

`DashboardPerformancePostgresIT` uses real PostgreSQL 16 with the test-only
migration-060 v3 reporting view, testing delayed clean, on-time erroneous,
unreviewed, deleted, zero-count company, date basis, workflow filter and
immediate user revocation. MockMvc and application tests cover 401/403,
bad filters, exact performance JSON keys, no-store and the closed public
snapshot endpoint.

Remaining work: duplicate trends, recent notes, backlog/SLA charts,
full executive/operations/company dashboards, company metrics snapshots,
browser E2E, migration/schema parity. No original .NET repo, main branch,
production database, Docker deployment or deferred Gitea changes.
