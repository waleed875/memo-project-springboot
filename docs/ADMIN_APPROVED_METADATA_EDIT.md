# Phase 36 — approved-note administrator metadata edits

New internal application command, port, service, JDBC repository and Spring
wiring allow an active ADMIN user to change number, writing date, arrival date,
factory or production line on an existing APPROVED note.

## Contract and guard rails

- The repository rechecks the ADMIN role in auth.users and locks the note,
  with the shared transaction advisory lock acquired first.
- Only APPROVED is accepted, with exact expectedUpdatedAt concurrency check.
- The company and type MUST equal the originals. Reclassifying a confirmed
  memo would require explicitly migrating linked notes, source references and
  indicator definitions; it is **not** attempted here.
- The positive numeric parser supports Arabic digits. Duplicate lock key and
  numeric/year query match the existing Java create path and exclude self.
- Existing inactive factory/production line may be preserved, mirroring the
  legacy C# administrative exception; new targets must be active and belong
  to the existing company/factory.
- The SQL UPDATE touches only allowed columns and updated_by. Status,
  reviewer_id, review_started_at, return_reason, approved_by, approved_at,
  notes, indicators, attachments, sources, links and flags are untouched.
- One ADMIN_EDIT_NOTE record captures old and new metadata and unchanged
  workflow fields within the same transaction. Audit failure rolls back
  metadata and revision.
- A full PostgreSQL 16 fixture after original migrations 001–070 tests
  successful Arabic numeric change, workflow preservation, duplicates,
  auth/stale checks and audit rollback.

## Not equivalent to all C# SaveNoteAsync writes

The original C# allows a broader ADMIN edit. Changing a company/type on an
approved memo, updating indicators/media/links as one multi-field command,
duplicate resolution, and public HTTP PUT still require reviewed business
rules, transaction orchestration and client/security tests. This is an
internal-only safe subset. No C# repo changes, no main merge or deployment.
