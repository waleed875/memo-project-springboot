# Phase 37 — duplicate resolution (internal only)

## Original C# contract reference

Pinned, read-only original code at
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`:
`NoteService.ResolveDuplicateAsync`, `SaveNoteAsync`, and migrations
`028`, `055`, `062`.

The original workflow has **three distinct decisions**, not a generic
"ignore duplicates" switch:

1. **COMPANY_DUPLICATE_NUMBER** — create the *different* memo with the same
   number and save a duplicate incident with `created_note_id` plus
   `COMPANY_DUPLICATE_NUMBER` audit.
2. **COMPANY_RESEND** — *do not create another note*, insert a
   `COMPANY_RESEND` incident with `created_note_id = NULL` and audit.
3. **DATA_ENTRY_DUPLICATE_ATTEMPT** — *do not create a memo or company incident*;
   write an audit event only.

## Implementation

- Existing `NoteCreateCommand` has optional
  `NoteDuplicateResolution(classification, matchedNoteId)`; old 4- and
  6-argument commands still work unchanged.
- `JdbcNoteCreator` re-checks the current company/type/year/number match
  after acquiring the same C# duplicate advisory lock, seed `127`. A
  matching original is mandatory when an explicit decision is supplied.
  It persists the new memo and the incident/audit in one transaction.
- SHA-256 idempotency fingerprints for pre-existing commands are preserved;
  explicit resolution details become part of the fingerprint, preventing
  accidental reuse of an operation ID with a changed original/decision.
- New `NoteDuplicateDecisionService` and
  `JdbcNoteDuplicateDecisionRepository` provide the two no-create decisions.
  Both check the actor's current database role, reject stale matches, and
  share the C# advisory key. Invoice-specific matching and lock-key date
  semantics are supported for no-create decisions.
- Original migration 055's unique active incident constraint is used as-is;
  there is no new production migration.

## Verification

The real `JdbcNoteCreator` and no-create decision repository are run under
the original 001–070 PostgreSQL 16 schema, with tests for all three decisions,
idempotent replay, changed/stale matched IDs, no-write classification,
incident/audit counts, rollback on incident insert failure, and rollback of
resend incident when audit fails.

## Remaining blockers

This does **not** expose a public duplicate-resolution route. The original
C# `SaveNoteAsync` and the original React client have not been executed
end-to-end against the new write path, and historical-data/old-client
coexistence still needs validation. The create subset does not support
invoice-specific note creation or every legacy flag/source variant. No
cross-runtime idempotency-fingerprint interoperability is claimed.
Check security, rate limits, request validation, duplicate-report aggregates,
and operation replay through HTTP before publishing the endpoint.

Original .NET repository unchanged. Draft feature branch only; no main merge,
production deployment or Gitea work.
