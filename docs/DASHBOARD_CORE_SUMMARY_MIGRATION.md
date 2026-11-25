# Phase 44 — internal dashboard scalar summary & legacy SLA verification

**Not** the public C# `GET /api/dashboard/snapshot` replacement. This
phase exposes an intentionally separate, ADMIN-only, read-only
`GET /api/internal/dashboard/summary` returning `summary`, `sla` and
`effectiveFilters`. All other nested snapshot arrays, report metrics,
comparisons and executive/operations/company views are still missing.
The legacy snapshot route remains blocked.

## Rules pinned to original .NET implementation

Reference: original `DashboardQueryService.cs`, `DashboardDtos.cs`,
`MetricsCalculator.cs` at
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

- `dateBasis` defaults to `WritingDate` and accepts `ArrivalDate`
  case-insensitively. Date bounds are inclusive, with the selected period
  expression. `entryCutoffDate` filters by **arrival date**, regardless of
  dateBasis, as in the original `BuildSnapshotSql` transformation.
- Time-related `notesToday` and `notesCurrentMonth` also use the selected
  date basis and today's business day in `Asia/Damascus`, not the machine
  server time zone.
- Approved and pending-review status counts match the C# status list.
  Late threshold is strictly **greater than five days** between
  `writing_date` and `arrival_at`; 0–5 is on-time. Null writing dates
  don't enter either. Late same/cross month compares both calendar months.
- Duplicate incidents are not counted as notes. Each COMPANY_RESEND uses
  `matched_note_id`, each COMPANY_DUPLICATE_NUMBER uses `created_note_id`.
  `duplicateAffectedNotes` counts the distinct union, and
  `nonDuplicateNotes` = max(0, totalNotes - affected).
- Legacy SLA has a distinct denominator of valid-date-order notes;
  `onTimeRate` is null when that denominator is zero and rounds to two
  decimals with midpoint away from zero.
- The database reader is `@Transactional(readOnly=true, isolation=REPEATABLE_READ)`
  with a 30-second timeout and a fresh active ADMIN role re-check.
  No SQL identifiers or expressions are interpolated from untrusted request
  data; only the two original date-basis choices are permitted.

## Security and tests

The new HTTP route gets actor identity only from a DB-revalidated session,
returns no-store, denies non-ADMIN roles, validates dates and status and
maps authentication and query errors to 401/403/400/503. The service has an
injectable business clock for deterministic behavior.

`DashboardSummaryPostgresIT` uses PostgreSQL 16 and isolated legacy-shaped
schema fixtures to exercise date boundaries, duplicate incident reference
mapping, delay thresholds, aggregates, SLA, company and status filtering,
revocation and the empty case. MockMvc tests enforce security and JSON
contract. No React E2E or complete legacy snapshot parity is claimed.

The previous phase's most recent GitHub verify run failed due to Maven Central
HTTP **429** while fetching the Maven jar plugin; an earlier verify run for
the same commit succeeded. This is documented as a transient external
dependency failure, not evidence of a successful final rerun.

No original .NET repository edits, no main merge, no production database,
Docker deployment or Gitea changes.
