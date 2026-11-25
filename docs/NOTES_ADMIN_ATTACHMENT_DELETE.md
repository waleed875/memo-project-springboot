# Restricted administrator attachment removal and primary synchronization

This stacked draft ports a narrow internal subset of the legacy C#
DELETE /api/notes/{id}/attachments/{attachmentId} behavior, **without**
making the HTTP route publicly available.

## Transactional contract
- Only authenticated ADMIN may invoke this use case. Active role is
  rechecked from auth.users / auth.roles within the transaction.
- The global write gate must allow mutations. Require an exact memo
  expectedUpdatedAt and lock core.notes FOR UPDATE.
- DELETE the file only when its attachment ID belongs to the given memo;
  a foreign memo attachment ID returns a missing-file error without writes.
- Sync core.notes primary attachment metadata and bytea to the oldest
  remaining attachment by created_at (UUID ascending as a deterministic
  tie-break). Clear primary attachment columns when none remain.
- Preserve status, current reviewer, approval metadata, source records,
  note links and review history.
- In the same transaction, record NOTE_ATTACHMENT_DELETED in audit.logs,
  using the deleted attachment ID as record_id and metadata-only JSONB.
  No raw binary bytes are logged. A failed audit rolls back the DELETE and
  primary-column changes.

## Compatibility boundaries and gates
The legacy C# endpoint checks an authenticated user and CSRF but does not
restrict role to ADMIN or require an updated-at token. This draft intentionally
uses a stricter admin-only, optimistic concurrency contract and **is not**
claimed fully HTTP compatible yet.

This phase excludes file downloading, multi-upload, HTTP exposure, note-source
and link synchronization, and complete 001–070 schema replay. The test
fixtures are reduced PostgreSQL schemas. Golden-master parity, concurrency
tests, and React E2E remain required before merge or production deployment.
No changes to the original .NET repository or main branch.
