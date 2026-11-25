# Phase 35: transactional metadata edits of owner drafts

## Implementation

The internal owner-only `NoteDraftEditCommand` accepts an optional
`NoteDraftMetadata` snapshot replacement containing note number,
company, note type, factory, production line, writing date and arrival date.

- A null metadata value preserves existing metadata, maintaining compatibility
  with the older six- and nine-argument commands.
- A non-null value is a complete metadata replacement; null factory/line
  fields explicitly clear those optional associations.
- The write adapter checks DATA_ENTRY role in the database, ownership,
  DRAFT/RETURNED_FOR_CORRECTION status, expectedUpdatedAt, active catalog,
  factory and line hierarchy and valid dates, all in the transaction.
- Number is normalized and parsed (including Arabic digits) with the same
  validation used for new-note creation. Duplicate checking excludes the
  current memo, uses the C#-derived numeric+year semantics and obtains the
  same hashtextextended duplicate key/seed 127 as JdbcNoteCreator.
- Effective indicator definitions come from the **new** note type when it
  changes. NoteSavePolicy validates the candidate and indicators before
  persistence.
- Existing counterpart links or an existing note_sources row prevent company
  or type replacement. Such changes require a dedicated link/source migration
  workflow; this action does NOT silently unlink another memo or detach source
  history.
- UPDATE_NOTE_METADATA writes old/new metadata JSON to audit.logs in the same
  transaction as metadata, text, indicators, review history, attachments and
  explicit link synchronization. Failure rolls all changes back.

## Full original schema tests

Extended `LegacyFullSchemaOwnerDraftMediaLinksIT` on PostgreSQL 16
following exact original C# migrations 001–070:

1. Successful Arabic-number/company/date change with metadata audit.
2. Duplicate number refused without partial edits or history.
3. Company change with existing link and trigger-created source refused.
4. Metadata audit failure rolls back all edits.
5. Earlier phase-34 resubmit/attachment/link tests are retained.

## Not yet full C# SaveNoteAsync

This does not support approved-note metadata editing by ADMIN, duplicate
classification/resolution or changing company/type while links or source
records exist. It cannot alter invoice/loot flags and provides no public
POST/PUT route. The original C# server's complete workflow/API behavior still
needs parity and E2E verification, especially concurrency between duplicate
lock acquisition and other writers. The original repository is unchanged.
No main merge or deployment.
