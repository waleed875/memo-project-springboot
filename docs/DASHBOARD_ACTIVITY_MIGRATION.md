# Phase 48 — restricted dashboard duplicate trends, recent notes and review backlog

The previous Stage 47 commit
`4e2d7e216450f5c0820556a04de2a90ccac4b612` passed all
four GitHub Actions checks (`verify`, `parity`).

## API contract and architecture

`GET /api/internal/dashboard/activity` is a read-only ADMIN command/query,
not a replacement for the public `GET /api/dashboard/snapshot`.

It returns the original C# dashboard component names and item field names:
`duplicateTrend`, `duplicateBreakdown`, `recentNotes`,
`backlogByAge`, and `effectiveFilters`. Each is strongly typed.
The session resolves the actor, the application service verifies ADMIN,
and a fresh DB role check occurs inside one read-only PostgreSQL
`REPEATABLE_READ` transaction with a 30-second timeout.

The controller accepts the existing dashboard date-basis, from/to,
company/factory/line/type/status and entry-cutoff filters. Missing
`dateBasis` defaults to `WritingDate`. Dates have inclusive bounds,
the cutoff always uses arrival date and status values are validated. To
avoid unbounded zero-fill, at most 240 calendar months can be requested
with both dates; no hidden truncation of the user request occurs.

## Original-source business logic

Pinned reference: original .NET
`DashboardQueryService.cs` and `StatusTranslations.cs` at
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

- Duplicate incidents have a **reference note**: `COMPANY_RESEND`
  uses `matched_note_id`, while `COMPANY_DUPLICATE_NUMBER` uses
  `created_note_id`. Only active reference notes matching filters count.
  `duplicateTrend` groups incidents by reference note date basis and
  renders Arabic month/year labels. If both from/to are supplied, gaps
  are filled by zero-valued months.
- `duplicateBreakdown` reports counts for supported classifications,
  with exact Arabic names from the original `StatusTranslations`.
- `recentNotes` returns at most 10 active notes sorted by
  `arrival_at DESC, created_at DESC` (a UUID tie-breaker makes ties
  deterministic) with company/factory/line/type names, status translations,
  original note and arrival/writing dates. `isLate` is the original
  strictly-more-than-five-calendar-day threshold.
- `backlogByAge` includes only COMPLETE, HAS_ERRORS, NEEDS_REVIEW and
  UNDER_REVIEW. It anchors age on
  `COALESCE(review_started_at,created_at,arrival_at)`, compares dates
  to the `Asia/Damascus` business day and emits only non-empty bands:
  less than one day, 1–3, 4–7, over 7 days. The output order and
  Arabic labels match C#.

## Verification and limits

MockMvc checks HTTP 401/403/400, no-store, JSON shape and that
`/api/dashboard/snapshot` remains blocked. Service tests validate the
business clock, roles, range protection and labels.
`DashboardActivityPostgresIT` executes the queries against PostgreSQL 16
Testcontainers with supported duplicate incident classifications,
date-basis differences, zero-month fill, deleted notes, review-start
anchors, status filtering, active DB role revocation and empty periods.

These isolated test-schema fixtures are not a full migrated production
database or React browser parity run. The complete legacy snapshot still
needs incoming vs approved, completeness, company metrics/current vs
previous-period, views composition and further integration tests.
No changes to original .NET repository, `main`, production data,
Docker deployment or Gitea.
