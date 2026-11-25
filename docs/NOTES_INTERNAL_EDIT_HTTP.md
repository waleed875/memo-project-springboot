# Phase 39 — opt-in internal note edit commands

The original legacy `PUT /api/notes/{id}` stays blocked until complete
`SaveNoteRequest` and React parity testing. This phase exposes two narrower
commands backed by existing transaction-tested application services.

## Owner draft / returned-for-correction

`PUT /api/internal/notes/{id}/draft` (JSON), role **DATA_ENTRY** only.

```json
{
  "expectedUpdatedAt": "2026-11-24T10:20:30",
  "notes": "Corrected memo",
  "saveAsDraft": true,
  "markAllIndicatorsCorrect": false,
  "fieldValues": [
    {"fieldId": "aa2f9fe0-248b-4686-870a-f3476cbfc79a", "value": "Correct", "inactiveReason": null}
  ]
}
```

- All five root keys are required, including `notes` (explicit `null` may clear
  optional text). `fieldValues` must supply the desired full current set:
  the transactional repository removes no-longer-supplied indicator values.
- This path **preserves** existing attachments, links and note identity metadata.
  These options are rejected if supplied here, rather than silently dropped.
- The repository rechecks live actor role, ownership, status (DRAFT /
  RETURNED_FOR_CORRECTION), exact `updated_at`, indicator definitions,
  workflow transitions, audit and post-save revision inside the transaction.
  `saveAsDraft=false` may submit the updated note for review.
- Times are PostgreSQL local timestamps in ISO-8601 form, without `Z`
  or timezone offsets. Clients must use the unmodified precision returned
  from the server, not a rounded or timezone-shifted timestamp.

Successful response: `{"id":"uuid","status":"DRAFT","updatedAt":"...","message":"..."}`.

## Administrator-only text edit

`PUT /api/internal/notes/{id}/admin/text` (JSON), role **ADMIN** only.

```json
{"expectedUpdatedAt":"2026-11-24T10:20:30","notes":"Updated text"}
```

The `notes` key is always required and may be explicitly `null`.
Approval status, reviewer, other note fields and review history are not writable
through this endpoint. The existing repository uses pessimistic locking, an
optimistic timestamp check, a fresh DB role check and audit logging in one TX.

## HTTP security and errors

- Missing active application session: 401.
- Missing CSRF / insufficient active role / owner mismatch: 403.
- Invalid input or unknown fields anywhere in the JSON payload: 400.
- Outdated revision, invalid status or read-only gate: 409.
- Other unexpected errors: framework 500, no attempt to coerce into 200.

No endpoint accepts actor/reviewer identity from the body. Request CSRF remains
mandatory. MockMvc HTTP tests include role separation, data mapping, unknown
legacy fields, missing keys, version conflicts and original PUT denial.

## Exclusions and release checks

This is **not** the full original C# update contract. It does not implement a
public controller for admin indicator edits, metadata changes, attachment
replacement/removal, source transitions, LOOT/invoice variants, or atomic
multi-operation legacy PUT behavior. Tests for full legacy React payloads,
real-browser CSRF/cookies, old/runtime coexistence, large payload sizes,
concurrency and schema parity are still necessary before production use.

Branch and draft PR only; the .NET source repository, main, production DB and
Docker deployment are not changed.
