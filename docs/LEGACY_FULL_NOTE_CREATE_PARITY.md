# Phase 33 — whole-note creation on the unchanged original schema

This stage tests the restricted internal Spring Boot `NoteCreateService` +
`JdbcNoteCreator` against a disposable PostgreSQL 16 database after applying
the original unmodified C# migrations **001–070**, pinned at
`dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

## Business and transactional assertions

- Creation of a submitted ENTRY note persists active field indicators,
  attachment bytes in both primary and attachment tables, a corresponding
  EXIT-note link plus trigger-maintained source reference, status COMPLETE,
  one SUBMIT_FOR_REVIEW history event and the expected CREATE_NOTE,
  SAVE_INDICATORS and LINK_NOTES audit events.
- Exact idempotency-key replay returns the existing note without repeating
  attachments, links, history, audits, or trigger-side effects.
- A DRAFT can remain incomplete and has CREATE_DRAFT history.
- Incomplete required indicators and changed idempotency replay are rejected.
- A deliberately rejected late SAVE_INDICATORS audit row rolls back the
  entire note, all field rows, attachments, links, trigger-side references,
  review history and idempotency reservation.
- Linking an EXIT note belonging to another company is rejected without
  creating any partial note.

## Source compatibility boundary

These are **contract-derived Spring Boot integration tests**, not execution
of the original C# SaveNoteAsync code on the same fixtures. The original
C# SaveNoteAsync uses CREATE_NOTE, SAVE_INDICATORS, SUBMIT_FOR_REVIEW and
CREATE_DRAFT among its audit/history action names. The Spring implementation
intentionally uses a versioned, Java-specific creation fingerprint; its
idempotency key is NOT byte-compatible with the original C# fingerprint.

Full original C# service response, duplicate-resolution workflows, historical
data migrations, concurrent edits, and UI/HTTP behavior still require
golden-master and security tests before the restricted create core may be
opened as an external endpoint.

No original .NET repository modifications, no merge to main and no deployment.
