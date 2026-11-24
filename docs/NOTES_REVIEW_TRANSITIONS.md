# Review workflow transitions: limited migration PR

This stacked draft implements **only** three original memo review actions:

- POST \`/api/notes/{id}/start-review\` — REVIEWER / ADMIN
- POST \`/api/notes/{id}/return\` — same reviewer who claimed it
- POST \`/api/notes/{id}/release-review\` — ADMIN only

Responses preserve the legacy Arabic \`message\` fields.

## Business rules and boundaries

Legacy \`NoteService\` and \`NotesEndpoints\` are the functional reference.
Start is permitted from COMPLETE, HAS_ERRORS and NEEDS_REVIEW if unclaimed,
not self-created and not soft deleted. Return is allowed only on the assigned
reviewer's UNDER_REVIEW note and requires nonempty reason. Release requires
ADMIN and nonempty reason, moves UNDER_REVIEW to NEEDS_REVIEW, clears reviewer,
and leaves return_reason unchanged to match the original statement.

Before SQL: database-revalidated session, correct role, global fail-closed
write gate. Inside SQL transaction: user and active role revalidation,
\`SELECT FOR UPDATE\`, optional \`expectedUpdatedAt\` compatibility check,
conditional status mutation, history event, and structured old/new JSONB audit.
The original C# schema has an updated_at trigger from migration 003.
The Java implementation relies on that trigger; the isolated PostgreSQL test
reproduces it.

The active transaction rolls back if the audit insert fails. Concurrent
reviewers serialize on the note row. Anonymous/unsupported roles cannot access
the adapter; Spring Security CSRF is required on every POST.

## Not done in this PR

Approval requires the original validation of all currently active indicator
definitions, status transitions and ownership, so POST \`/{id}/approve\`
remains denied; there is **no fake approval shortcut**. No legacy note
POST/PUT is opened.

Production blockers: full migrations 001–070 (test fixture is deliberately
partial), C# golden master comparison including timestamp semantics and
responses, real React workflow tests, active-user role concurrency checks,
CI green and code review. Do not deploy, do not merge to main, do not edit
the original .NET repository.

