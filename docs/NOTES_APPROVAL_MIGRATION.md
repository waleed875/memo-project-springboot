# Approval workflow migration — draft PR

This PR ports the original \`POST /api/notes/{id}/approve\` action on top of the
unmerged reviewer workflow. It does not enable legacy note create/update.

## Original behavior

Reviewers and admins only. The caller cannot approve their own note and must
currently hold the note's UNDER_REVIEW reservation. Optional optimistic
\`expectedUpdatedAt\` is honored. Active indicator definitions, required values,
the note number, company/type, chronological writing/arrival dates and
Asia/Damascus operational today are checked against **persisted** data, not the
client request. Inactive definitions do not demand values; all persisted enum
codes are still validated.

The approved note receives status APPROVED, approved_by/approved_at, clears
reviewer_id and return_reason, and preserves review_started_at for the final
review cycle (migration 033). This transaction writes APPROVE to
core.note_review_history and APPROVE_NOTE to audit.logs. An audit write failure
rolls back approval and history.

## Engineering safeguards

- Framework-free NoteApprovalPolicy reuses NoteSavePolicy validation.
- MasterDataWriteGate is fail closed; session identity and role are verified
  before repository access and rechecked from active database users.
- Lock note FOR UPDATE; lock effective note-type field definitions FOR SHARE,
  and field-value rows FOR SHARE before validation to avoid concurrent edits.
- Spring Security requires CSRF. The response message matches C#.
- Detailed JUnit, Spring MVC and PostgreSQL 16 Testcontainers tests cover
  permissions, missing/unreviewed required fields, stale version, reapproval
  conflicts, audit rollback and timestamp/audit semantics.

## Still required before deployment

Full 001–070 original schema migration replay, original C# golden-master
comparison, further concurrent admin-definition-edit tests, React acceptance
tests, and code review. The fixture is intentionally partial. This is a
stacked draft PR; never merge to main or deploy as-is.
