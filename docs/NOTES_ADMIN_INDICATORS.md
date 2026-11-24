# Administrator indicator edits — internal transactional migration

## Source of business rules

Compare with original C# \`NoteService.SaveNoteAsync\` and
\`NoteValidator.Validate\`; the source repository remains read-only.
On existing ADMIN edits, the original C# method:
- preserves status, reviewer and approval metadata,
- filters incoming values to effective active fields,
- validates all required active fields for non-draft saves,
- **does not prune inactive/historical values**,
- upserts submitted active values and records \`ADMIN_EDIT_NOTE\` and
  \`SAVE_INDICATORS\` audit actions without adding review-history records.

## Implemented safely as a narrow internal command

\`AdminNoteIndicatorEditCommand\` accepts only note UUID, exact expected
\`updated_at\`, \`markAllIndicatorsCorrect\`, and submitted indicator
values including optional reasons. It **does not** modify note number,
dates, catalog, attachment/source/link data, free notes text, workflow
status, review reservation or approval timestamps.

Application checks role ADMIN, database-revalidated session, and fail-closed
write gate before a JDBC transaction. Database adapter revalidates active
ADMIN role, locks the memo row, checks exact expected revision, acquires
the original note-definition advisory key (seed 712), locks metadata and
existing indicator rows, applies \`NoteSavePolicy\` to submitted effective
values, and atomically upserts without pruning historical values. It updates
\`updated_by\` solely to advance the note's updated_at trigger, then records
structured old/new \`ADMIN_EDIT_NOTE\`, \`SAVE_INDICATORS\`, and optional
\`MARK_ALL_INDICATORS_CORRECT\` audit entries.

Any validation or audit failure rolls back all changes. The internal command
rejects DRAFT memo edits: this covers only the non-draft C# administrative
path, not the separate saveAsDraft path.

## Explicit non-goals and release gates

Not a full C# \`SaveNoteRequest\`; does not expose POST or PUT /api/notes.
No change to the C# original, no merge into main, no production deployment.
Before enabling HTTP: full original migrations 001–070 (the test schema is
partial), golden-master business comparisons, concurrency tests including
master-data definition edits, complete admin edits, and React E2E.
