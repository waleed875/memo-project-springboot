# Phase 45 — internal dashboard distributions and timeliness comparisons

CI gate: PR #44 commit `6d45c33927f988895e018947db7db00729122771`
completed all four verify/parity runs successfully.

This phase adds ADMIN-only
`GET /api/internal/dashboard/distributions`, not the full
`GET /api/dashboard/snapshot`. The latter stays blocked.

## Original-source parity

These chart slices follow pinned .NET
`DashboardQueryService.cs`, `DashboardDtos.cs`, and
`StatusTranslations.cs`:

- `monthlyActivity` uses the chosen writing/arrival basis, month key
  `yyyy-MM`, Arabic month/year label and counts. With both dates supplied,
  each intervening calendar month is represented, including zeros.
- `statusDistribution` counts the same seven workflow states and carries
  the original Arabic translations.
- `companies` includes active, nondeleted companies with zero counts and
  sorts by descending count then name; the optional company filter applies
  to both company and note sets.
- `timelinessByCompany`, `timelinessByFactory`, and
  `timelinessByLine` include eligible, late and on-time counts, with
  a strictly greater-than-five-days lateness threshold. The rate
  denominator is eligible notes and returns null when zero. Factory/line
  collections exclude deleted dimension records and are capped at 10.
- Filters match the Phase 44 `DashboardSummaryCriteria`, including
  `dateBasis` normalization, date-inclusive bounds, status and
  arrival-based entry cutoff.
- The SQL runs as read-only, repeatable-read transaction with timeout,
  checked CURRENT active ADMIN DB role and parameterized query values.
  Fixed SQL expressions are selected only via the validated date-basis
  choice, never from unsanitized input.

**Important limitation:** Factory and line indicator-quality metrics rely
on original `reporting.vw_note_indicator_facts_v3`. They have NOT been
implemented here. Rather than publishing zeros as if they were measured,
the new internal `TimelinessItem` DTO omits quality columns. Likewise,
do not treat this restricted diagnostic contract as the complete legacy
`PerformanceItem`, or open the original snapshot route.

## Verification and next stage

MockMvc covers auth, denied roles, range validation and the HTTP projection.
PostgreSQL Testcontainers tests cover real SQL aggregation, cross-month
boundaries, status/company filters, null rates, empty month buckets and
current role revalidation. The full historical schema, all dashboard views
and the browser's original JSON structure still require parity testing.

No writes to original .NET repository, main, production DB, Docker or Gitea.
