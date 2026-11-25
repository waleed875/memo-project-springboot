# Phase 38 — explicit internal note-create HTTP command

This is an **opt-in, restricted command interface**, not a replacement for
the legacy `POST /api/notes` JSON model. The legacy route remains denied.
It is bound to the existing authenticated session, Spring CSRF token, and
the existing DATA_ENTRY / ADMIN application authorization. JDBC checks the
actor's active database role again and performs the write atomically.

## Endpoint

`POST /api/internal/notes`, `Content-Type: application/json`.

```json
{
  "operationId": "8ecf7b38-5991-476c-aa47-c4b6b08e9405",
  "noteId": "444492db-d814-44e9-b344-63593aaf31f8",
  "candidate": {
    "noteNumber": "123",
    "companyId": "66fed04c-5bd6-41b2-b3c6-58c1a3977136",
    "noteTypeId": "a1ef3978-56d6-4c28-82eb-a61ff40852b8",
    "factoryId": null,
    "productionLineId": null,
    "writingDate": null,
    "arrivalAt": "2026-11-24",
    "saveAsDraft": true,
    "markAllIndicatorsCorrect": false,
    "fieldValues": [{"fieldId": "d99517ad-e783-4c18-afbd-a135deae80a1", "value": "Correct"}]
  },
  "notes": "Sample",
  "relatedNoteIds": [],
  "duplicateResolution": null
}
```

All UUIDs must be nonzero; `operationId` and `noteId` are **client-generated
stable identifiers** to make retries deterministic through the existing JDBC
idempotency ledger. A retry of the same command should reuse both IDs.
The arrival date is mandatory; indicator enum spellings are case-sensitive.
The same SQL catalog/duplicate/required-field validations run under the
transaction, not on an earlier HTTP lookup.

All unknown fields are rejected recursively. Multipart attachment uploads,
invoice/loot/source fields, automatic note IDs, old `SaveNoteRequest`
envelopes, and the no-create duplicate classifications are deliberately
unsupported by this route. The optional explicit duplicate resolution may
only be `COMPANY_DUPLICATE_NUMBER` with `matchedNoteId`; the repository
re-checks the match under the original advisory lock.

## Response and security

- `200`: `{"id":"...","message":"تم حفظ المذكرة بنجاح."}`.
- `400`: invalid or unsupported command data.
- `401`: no valid authenticated server session.
- `403`: missing CSRF or insufficient role.
- `409`: duplicate/stale resolution, read-only mode, or operation replay conflict.

MockMvc covers mapping, CSRF, unauthorized and viewer rejection, unsupported
legacy input, invalid indicator/UUID, and forwarding the duplicate decision.
Existing PostgreSQL tests cover atomic create and idempotency. These HTTP
tests do **not** yet prove real-browser React compatibility or a public
production release.

## Gates before opening legacy POST

1. Reconcile the full C# `SaveNoteRequest.Note` object with Spring's persisted
   fields, including invoices, sources, files, timestamps and omitted flags.
2. Define a client-compatible idempotency key/operation-ID contract and test
   retry after timeout and old-client requests.
3. Cover authorization, CSRF, real Postgres rollback, duplicate incident
   transactions, upload limits, and browser E2E against the React client.
4. Verify existing historic-data behavior and old/new writer coexistence.

The original .NET repository was only read. No main merge, production rollout,
or Gitea deployment is part of this increment.
