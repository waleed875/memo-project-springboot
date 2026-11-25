# Phase 34 — owner draft and correction resubmission, media + links

## Implemented internal capability

The existing restricted DATA_ENTRY owner draft-edit command now accepts
optional new attachment bytes and explicit link-set synchronization.

- Old six-argument commands retain their original behavior. Null attachment
  preserves the primary attachment and all existing attachment rows. Supplying
  a valid attachment appends to core.note_attachments, updates the legacy
  primary attachment columns and records a metadata-only
  NOTE_ATTACHMENT_UPLOADED event (no binary bytes in audit).
- Both expectedRelatedNoteIds and relatedNoteIds must be supplied together
  to synchronize relationships. Supplying neither leaves links untouched;
  supplying explicit empty desired links clears existing links. The expected
  complete prior set is required to prevent silent overwrites from concurrent
  legacy/source writers.
- Source and target type/company eligibility, non-self and duplicate IDs,
  no LOOT source links, and a maximum of 100 links are checked.
- The existing application service still restricts the operation to a
  DATA_ENTRY actor; the JDBC transaction rechecks the active database role,
  ownership, DRAFT or RETURNED_FOR_CORRECTION state, and expectedUpdatedAt.
- The shared transaction-level link advisory lock is acquired BEFORE the
  note's FOR UPDATE lock. The existing C# migration 063/070 triggers remain
  authoritative for bidirectional source-reference synchronization.
- Indicators, status/history, notes, attachments, links and audits are saved
  atomically; a late audit failure rolls back all changes.

## Source-code parity boundary

The original C# SaveNoteAsync edits number, company, type, dates,
attachments, links and flags together, subject to role and workflow rules.
This implementation deliberately leaves identity, date, company, factory,
source and invoice/loot fields immutable, and does not expose a general
HTTP PUT/POST. It is a secure, narrower working server capability, not yet
the full equivalent of SaveNoteAsync.

The new PostgreSQL 16 full-schema CI test covers returning a note for
correction and resubmitting it with indicators, attachment and link,
stale source references, and rollback on audit failure. Additional tests
are required for link clear, additional attachments, concurrent edits and
the production endpoints before launch.

## Remaining server work

Full SaveNoteAsync number/catalog/date changes, duplicate resolution writes,
media deletion/replace semantics, complete API contracts, UI integration,
historical migrations with production data, legacy writer coexistence,
security review and deployment testing are still outstanding.

No original .NET repository changes, no main merge or production deployment.
