# Internal administrator text-only edit core

## Scope and parity boundary

This stacked draft introduces a **restricted** administrator edit command,
separate from DATA_ENTRY draft edits. It changes only the optional notes text
and \`updated_by\` on an existing, nondeleted note under an optimistic
\`expectedUpdatedAt\` lock.

The original C# \`SaveNoteAsync\` allows ADMIN to edit an existing note while
preserving its workflow status, reviewer reservation, approval metadata, and
historical indicators. This slice deliberately retains all of those unchanged,
including while a note is APPROVED or UNDER_REVIEW. It emits a structured
\`ADMIN_EDIT_NOTE\` before/after JSONB audit in the same transaction and does
**not** append a review-history event.

Application role check, global fail-closed write gate, active DB-role recheck,
\`SELECT ... FOR UPDATE\`, version comparison, update, and audit are enforced.
A failed audit rolls back the text edit.

## Missing capabilities

This is NOT the full admin \`SaveNoteRequest\`. Editing note number, catalog,
dates, active/historical indicators, attachments, sources, invoice/loot flags,
duplicate resolutions or links are not implemented. The general PUT endpoint
remains closed. Schema tests use reduced fixtures only, not the full original
001–070 migrations; C# golden-master and React E2E are required before merge
or deployment.

No change to the original C# repository or main branch.
