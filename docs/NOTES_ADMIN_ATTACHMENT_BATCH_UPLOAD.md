# Internal administrator multi-attachment upload — stacked draft

This slice follows the completed single-file upload and delete cores. It
ports the original legacy C# \`POST /api/notes/{id}/attachments\` append-only
behavior but does not open any HTTP multipart route.

## Core behavior

- Validate all files before persistence: safe filename, extension/signature,
  nonempty content, per-file 10 MiB signature policy.
- **Additional guardrails:** maximum 20 files and 40 MiB total.
- Database-revalidated ADMIN role only, and mandatory note expectedUpdatedAt.
  Original legacy HTTP accepted any authenticated administrator-interface
  user and did not require a version; this narrower command is intentional.
- The note row is locked \`FOR UPDATE\` before the batch. All attachments
  insert into \`core.note_attachments\` with the correct note, actor and bytes.
- The primary legacy \`core.notes\` attachment columns use \`COALESCE\`: keep
  existing primary file attributes if already non-null; initialize null
  fields from the first uploaded file. This **differs** from single upload,
  which intentionally replaces the primary attachment.
- Emit one metadata-only NOTE_ATTACHMENT_UPLOADED audit per inserted file
  using each attachment id as \`record_id\`. Approval/reviewer/status
  and review history remain untouched.
- All writes use a single \`@Transactional\` transaction; audit failures or
  invalid late attachments roll back the complete batch.

## Required before public API or release

- Full legacy 001–070 schema migrations; the PostgreSQL Testcontainers
  fixtures intentionally reproduce only related tables.
- C# golden-master contract and security review: batch atomicity improves
  on the original endpoint's partial-save-on-error behavior.
- Full React UI E2E, concurrent upload/delete tests, malware/deep file
  validation and operational limits.
- No general POST/PUT /api/notes, no public multipart endpoint, no merge to
  main or deployment. The original .NET repository remains untouched.
