# Internal administrator single-attachment upload (stacked draft PR)

This step ports the insert-and-primary-replacement behavior from the legacy
C# single-file \`POST /api/notes/{id}/attachment\` endpoint into a narrow
internal Spring Boot command. It does not expose that HTTP endpoint.

## Preserved behavior
- Validate a filename, file size and file signature through the existing
  NoteAttachmentPolicy, which enforces 10 MiB and the original supported types.
- Append one row to \`core.note_attachments\`, retaining old attachment rows.
- Set \`core.notes.attachment_file_name/content_type/data\` to the new file.
- Record NOTE_ATTACHMENT_UPLOADED in audit.logs.

## Additional security and transaction guarantees
- This limited command is available only to ADMIN, not all users of the C#
  legacy web endpoint. This narrower authorization needs explicit agreement
  in the later whole-HTTP compatibility review.
- DB-revalidated actor role, read-only write gate, expectedUpdatedAt and
  row lock protect write integrity.
- The attachment row, primary legacy columns, and audit event are in one
  PostgreSQL transaction; audit failure rolls back everything.
- Binary attachment bytes are not logged in audit JSON; file metadata and
  attachment ID are logged. Existing approval/reviewer/workflow fields remain
  unchanged.
- No public POST/PUT /api/notes route is enabled here.

## Release gates
Full legacy migrations 001–070 (this uses reduced fixtures only), C#
golden-master and authorization parity, malware/deep file validation,
React E2E and successful CI are required before opening the route or
deploying. No change to the original C# repository or main branch.
