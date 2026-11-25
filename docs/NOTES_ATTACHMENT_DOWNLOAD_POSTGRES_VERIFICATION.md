# Phase 42 — unblock PR 41 and prove PostgreSQL download authorization

## Root-cause analysis

The PR 41 backend verification workflow failed while creating the Spring
application context, even though parity checks passed. GitHub Actions job
139071832685 logs establish:

```
Cannot subclass final class io.github.waleed875.memo.modules.notes.infrastructure.JdbcNoteAttachmentDownloadReader
```

The JDBC reader combined `@Repository` + `@Transactional` with a `final`
class. Spring's CGLIB proxy could not subclass it. The repository is now
non-final so transaction advice works. Its public read port and authorization
SQL are unchanged.

## PostgreSQL 16 tests

The new `NoteAttachmentDownloadPostgresIT` uses disposable Testcontainers,
the repository's legacy-shaped test fixtures, and the production reader via a
transactional interface proxy. It verifies:

- An active DATA_ENTRY account can read an owned memo attachment but not
  another entry's attachment or a soft-deleted memo.
- Even ADMIN cannot retrieve an attachment using the wrong parent note ID,
  and unknown attachment/note IDs or unknown actors return no bytes.
- ADMIN, VIEWER and REVIEWER can retrieve active note attachments; an
  unsupported role cannot.
- Revoking a role, deactivating/deleting the account, deactivating the role,
  or soft-deleting a memo takes effect on subsequent requests.
- Empty binary data and legacy files over the endpoint's 10 MiB ceiling
  are excluded by the SQL predicate.
- Returned byte arrays are immutable defensive copies.

These are data-level tests, not an assertion that the complete original
001-070 migrated database or old React browser has been exercised. The
previous HTTP tests continue to cover CSRF, session and response headers.

## Dashboard findings: contract to port next

The pinned original C# implementation
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`
has `DashboardEndpoints.cs` and `DashboardQueryService.cs`:

- `GET /api/dashboard/snapshot`: date range, optional company/factory/
  production line/note type/status filters, entry cutoff and minSample 1..10000.
  Status values must be one of the seven legacy note states. The original
  endpoint normalizes `dateBasis` but its snapshot SQL shown in source uses
  **arrival_at** for date bounds; a migration must preserve/verify this
  subtlety rather than silently swapping to writing_date.
- The SQL includes duplicate incident classifications, delayed arrivals with
  the legacy five-day threshold, review states, company counts, trends,
  comparisons, recent notes and other aggregate dimensions. Returning a
  handful of counters from a new controller would be incomplete parity.
- `GET /api/dashboard/company-metrics`, `/lookups` and
  `/review-queue` are also documented original routes. The snapshot
  endpoint sends no-store/cache-control and a correlation ID.

The next dashboard implementation should first pin DTO field names from the
original models and add deterministic seeded SQL parity fixtures, then
implement the application port/JDBC query and HTTP endpoint with an active
session and bounded expensive filters. This phase does **not** open dashboard
routes or claim the dashboard has been ported.

## Release boundary

No changes to the original .NET repository, `main`, production database,
Docker deployment or Gitea. The PR stack is still draft. GitHub Actions
backend verification must pass on this commit before considering PR 41 fixed.
