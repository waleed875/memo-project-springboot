# Phase 53 — executable cross-runtime dashboard wire compatibility

The pinned reference C# source remains read-only at
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.
Phase 52 successfully passed four GitHub `verify` and `parity` checks.

## Independent executable oracle

The new workflow `dashboard-wire-parity-ci.yml` performs an executable
comparison, rather than relying only on reflection against a property manifest.

1. Run Java Spring MockMvc `GET /api/internal/dashboard/snapshot-preview`
   for four deterministic scenarios:
   - company-scoped with Arabic labels, dates and missing optional names;
   - global scope with null company summary;
   - completely empty/no-data result with null quality and SLA rates;
   - populated current/previous company metrics with nullable decimal fields.
2. Export **only** each flat `snapshot` child as JSON (not the diagnostic
   `parityGaps` envelope).
3. Compile an independent .NET 9 CLI that **links the original C#**
   `DashboardDtos.cs`, `DashboardContractDtos.cs`,
   `CompanyMetrics.cs`, and `DashboardContractBuilder.cs` directly.
   Deserialize the Java response into the real C# `DashboardSnapshot`,
   recompute `Views` using the original C# builder, serialize with
   `JsonSerializerDefaults.Web`.
4. Run the same Spring MockMvc scenario test again. Compare recursively
   every JSON property (exact key sets and types), array sizes and
   positions, nulls, booleans, Unicode and date-time strings. JSON
   numeric values are compared mathematically, so `75.00` and `75`
   are the same numeric value but `75` and `75.01` fail.

The C# oracle is intentionally independent of the Java view builder.
It tests the actual ASP.NET DTO serialization conventions and the C#
`DashboardContractBuilder` output over a common input. The compiler
uses original checked-out source; **no C# business code is copied or
edited** in the Spring repository.

## What remains unproven

This is a differential serialization and original view-composition test,
**not** a comparison of independent original C# SQL query results with
Spring JDBC on identical data. The scenarios are seeded from Java
read-side fixtures. C# DTO deserialization accepts these values and
rebuilds views, but this does not prove that the two backends would
generate the same values from the original live database.

The next gate requires database-level paired C#/Java outputs under
the same migrated PostgreSQL fixtures and historical date-basis/cutoff
choices. React browser E2E on the exact public route is still required.
The public `/api/dashboard/snapshot` remains explicitly closed.
No production DB, original .NET repo, main-branch merge or Docker/Gitea
changes are made.
