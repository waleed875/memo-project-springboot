# Phase 41 — internal attachment access and administrative link synchronization

These opt-in routes do not replace the original public note attachment or link
endpoints, which remain disabled. CSRF remains mandatory for writes.

## Files

- `POST /api/internal/notes/{id}/admin/attachments` — multipart with exactly
  one `file` part and `expectedUpdatedAt` form field, ADMIN only.
  Maximum file size 10 MiB and request 12 MiB. The existing service validates
  signatures/extension (image and PDF) and performs a transaction including
  primary attachment synchronization and audit.
- `DELETE /api/internal/notes/{id}/admin/attachments/{attachmentId}?expectedUpdatedAt=...`
  — ADMIN only; existing transactional repository checks `note_id` on the
  attachment and updates the primary file/metadata and audit atomically.
- `GET /api/internal/notes/{id}/attachments/{attachmentId}` — downloads only.
  The **current active database role**, **parent note ID** and **owner
  restriction** are all checked in the same read query as the bytes.
  DATA_ENTRY can read their own notes; ADMIN/REVIEWER/VIEWER can read active
  notes. No account/role/note match returns 404 without content, rather
  than disclosing another user's note existence. Missing session yields 401.
  Result is always `application/octet-stream`, `attachment` disposition,
  sanitized filename, `nosniff` and `no-store`. Oversized legacy files
  (over 10 MiB) are denied by this bounded endpoint pending streaming support.

## Links

`PUT /api/internal/notes/{id}/admin/links`:

```json
{
  "expectedUpdatedAt": "2026-11-24T10:20:30",
  "expectedRelatedNoteIds": [],
  "relatedNoteIds": ["25d8c8f3-ade9-49d3-a84e-5ea1bc10e99b"]
}
```

All three root fields are mandatory. The previous set is an optimistic
snapshot; `relatedNoteIds: []` deliberately clears links, while omission
is rejected. A maximum of 100 unique, non-zero, non-self UUIDs applies.
The existing ADMIN-only service and retrying transaction repository verify
live role, note revision, prior set, target validity and the shared source/link
lock protocol. It audits inserts/deletions with the original schema. Any
conflict returns 409, invalid inputs 400, insufficient role/CSRF 403.

## Release gates

The original .NET repository, main branch, deployment, historical data and
legacy endpoints remain unchanged. This stage is not a full REST API parity
or browser E2E claim. Follow-up verification must cover real Postgres
ownership/IDOR behavior on migrated datasets, multipart edge cases, legacy
large-file download streaming and old-client concurrency.
