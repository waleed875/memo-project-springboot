# Phase 51 — internal unified dashboard composition (NOT public snapshot)

## What this implements

A new **ADMIN-only** diagnostic route:
`GET /api/internal/dashboard/unified`.

It composes the existing tested dashboard read-side projections:
summary and SLA (44); monthly/status and companies (45);
field/company quality (46); unified time/quality performance (47);
duplicate/recent/backlog (48); incoming/approval and completeness (49);
canonical v4 company metrics and prior period (50).

`DashboardUnifiedService` runs them under one Spring
`@Transactional(readOnly=true, isolation=REPEATABLE_READ, timeout=60)`
method. The child JDBC ports join the transaction and each revalidates
the current active ADMIN database role. There is no joining JSON from
multiple HTTP endpoints, and all readers receive the same validated
`DashboardSummaryCriteria`. The business date uses an injected Damascus
clock, and the max 240-month range / minSample 1..10000 gates are applied
before any SQL. The service resolves the actor only from the session.

The response includes:
- `summary`, `sla` and all underlying typed slice objects;
- `views.context`, `executiveSummary`, `operationsSummary`,
  and conditional `companySummary` (only if a company filter exists);
- `generatedAt`, `minSample`, `effectiveFilters`;
- `parityGaps` explaining why this is **not** yet equivalent to the
  original public C# `DashboardSnapshot`.

The pure `DashboardUnifiedViewsBuilder` mirrors
`DashboardContractBuilder.cs`'s availability semantics:
`available` with `summary.totalNotes > 0`, else `no-data`
plus the Arabic explanatory message, without invented zero-based KPI
interpretations. It maps the same executive, operational and company
source collections. Previous company comparison is only available
when both current and previous company metrics exist and contain
positive total notes. Null decimal rate deltas remain null; rounding
is to 2 decimals.

## Known parity gaps (deliberately not hidden)

1. Original `TopErrorFieldItem` carries extra
   `totalFieldCount`, `correctFieldCount`, `errorFieldCount`,
   `unknownFieldCount`, `fieldAccuracyRate` columns. The current
   internal `TopErrorField` read model lacks them.
2. `DashboardUnifiedViews` shares section and item names, but is not
   yet a byte-/JSON-schema-equivalent port of
   `DashboardContractDtos.cs`. Its `context` uses
   `periodAndScope` and several items reuse earlier diagnostic records.
3. The original single-shot `GET /api/dashboard/snapshot` includes
   `isHealthy`, server timestamps and a full flat legacy payload,
   not nested diagnostic source records; this stage does not expose
   that public endpoint.
4. The isolated v4 company analytics fixture is not an end-to-end
   migration from the historical schema; React consumer E2E and
   cross-slice golden dataset comparisons remain open.

**Safety gate:** The public original
`GET /api/dashboard/snapshot` stays denyAll. Do not point the React
client at the internal route until a complete published contract and
browser regression tests pass.

## Verification

Unit tests validate the exact same actor, filters and business date
reaching every read port, absence of any query for non-admin/invalid
input, generated views with company context, no-data semantics,
and explicit gap metadata. MockMvc covers session denial,
filter validation, no-store headers and the still-blocked public
route. The existing PostgreSQL integration tests for each component
continue to run in CI. Additional full-composition DB and browser
integration tests are still required.

Before this PR, the stage-50 verify job revealed a Spring SQL script
split error (unterminated `$function$` body). The stage-50 branch
was corrected to execute the original canonical function as a
single SQL statement with `ScriptUtils.EOF_STATEMENT_SEPARATOR`
(commit `72f5ce6fe59ac1c3dff8bd9bd84a7b3d3251d57a`).
A green parity run does not substitute for the still-pending verify.

No edits to the .NET repository, main, production database, Docker
deployment or deferred Gitea build process.
