# Restricted draft edit / correction resubmission — internal transaction core

This PR builds on draft PR #16 and ports **part** of legacy C#
\`NoteService.SaveNoteAsync\`, without exposing \`PUT /api/notes/{id}\`.

## Supported scope

- Only a currently active DATA_ENTRY user may edit their own DRAFT or
  RETURNED_FOR_CORRECTION note. ADMIN editing is an intentionally different
  command, because the legacy C# implementation preserves workflow state and
  historical indicator values for administrator edits.
- Command edits **notes text and active indicator values**; it preserves
  note number, company/type, factory/line, writing/arrival dates, attachments,
  sources, loot/invoice flags, duplicate metadata, counterpart links.
- Optional save-as-draft, or submit/resubmit as COMPLETE/HAS_ERRORS following
  original NoteValidator required-field rules.
- Optimistic \`expectedUpdatedAt\` is required, rechecked on the locked note.
  Session role and ownership are rechecked using the database.
- The same note-type-fields advisory key/seed 712 as the migrated master-data
  writer guards effective definitions; active metadata is read under row locks.
- Full transaction: lock note, validate, update status/text, prune obsolete
  indicators, upsert active submitted indicator values, review history and
  old/new JSONB audit. Audit failure rolls back **every** mutation.
- The legacy \`RETURNED_FOR_CORRECTION\` resubmission clears reviewer_id,
  review_started_at and return_reason, and logs RESUBMIT_FOR_REVIEW.

## Why no HTTP PUT yet

The original SaveNoteAsync can also modify note identity/catalog/date fields,
legacy and additional attachments, sources/LOOT and invoices, duplicate incident
classification, multi-exit note links, synchronization and administrative edits.
Those features have to be migrated and tested **atomically** before exposing the
public SaveNoteRequest contract. Existing \`POST/PUT /api/notes\` stay denied.

## Validation gates

JUnit application auth/clock/write gate. PostgreSQL 16 Testcontainers ownership,
status, stale version, draft, correction resubmission, field pruning, retained
attachment metadata and rollback. Further gates: full migrations 001–070,
original C# golden-master, concurrent updates vs note field admin changes,
and actual React E2E. No merge to main, no production deploy and no edits to
the original repository.
