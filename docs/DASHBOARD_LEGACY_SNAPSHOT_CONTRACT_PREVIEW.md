# Phase 52 — pinned C# DashboardSnapshot JSON-shape preview

## Baseline and repair

Phase 51 `verify` failed in MockMvc:
`InternalDashboardUnifiedControllerTest.sessionAndRoleAreRequiredBeforeDatabaseWork`
because repeated Mockito `when(...).thenThrow(...)` attempted to invoke a
previously throwing stub while setting up the next one. Corrected to
`doThrow(...).when(service).read(...)` in PR #51 commit
`d4232f99c913ba381fab4b8574d3e378e6d28d29`.
The original stage-50 four GitHub checks succeeded after its test SQL
function loader was corrected.

## Original contract sources

Pinned, untouched original C# repository:
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`:
- `Backend/Models/DashboardDtos.cs` — 28 top-level DashboardSnapshot
  property names and nested summary, dates, recent and field statistics.
- `Backend/Models/DashboardContractDtos.cs` — nested context,
  executive, operations and optional company views.
- `Backend/Services/DashboardContractBuilder.cs` and
  `DashboardQueryService.cs` — view composition, no-data behavior,
  nullable prior comparisons, business time and report data.

`backend/src/test/resources/contracts/dashboard-snapshot-fields.json`
is a pinned C# property manifest. The framework-free contract test
compares every Java record's component names and order to that
manifest, covering the main snapshot and nested views.

## Internal preview only

`GET /api/internal/dashboard/snapshot-preview` is ADMIN-only and
returns an envelope, not the legacy flat endpoint:
```json
{
  "snapshot": { "summary": {}, "companies": [], "views": {} },
  "parityGaps": ["..."],
  "readyForPublicApi": false
}
```

The `snapshot` child is a 28-field `DashboardLegacySnapshot` DTO with
the C# property names; `views` contains exactly named nested contracts
including `context.period` and `context.scope`. It is assembled from
the same `DashboardUnifiedService` transaction and does not issue
unrelated queries. The `@JsonInclude(ALWAYS)` setting preserves nulls
where the original data contract includes nullable properties.

C# `TopErrorFieldItem` contains five extra fields which the original
`GetSnapshotAsync` presently does not populate. For contract fidelity,
the adapter uses its original defaults: the four nonnullable counts
are 0 and `fieldAccuracyRate` is null; they are **not** represented as
new measured results. The report DTO and view DTO share the same
top-field and recent-note projections. Dates from scoped date-only
fields are converted to local midnight `LocalDateTime`; server and
generation times use the business timestamp from stage 51.

The preview includes `isHealthy=true` and `errorMessage=null` only
after all read calls returned normally, matching the original
successful snapshot path. Database errors still return 503 via
existing error handlers, rather than emitting a healthy placeholder.

## Required gates before public release

1. Verify real PostgreSQL cross-slice values and all 28 top-level JSON
   fields, not just their names. The canonical original function must
   pass the full Testcontainers suite.
2. Compare C# System.Text.Json serialization against Java for dates
   (local/UTC kind), nullable fields, enum-like status labels, and
   decimals with golden HTTP fixtures.
3. Validate any semantic differences across source SQL queries
   (including differing arrival/creation cutoff use and minSample
   constraints) on historical seeded datasets.
4. Run React E2E against the exact legacy route and ensure no
   consumer regressions.
5. Only then consider opening `GET /api/dashboard/snapshot`.
   This PR leaves it blocked by `SecurityConfiguration.anyRequest().denyAll()`.

No original .NET repository changes, main merge, production DB changes,
Docker release or deferred Gitea integration.
