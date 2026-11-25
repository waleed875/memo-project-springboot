# Phase 31 — executable original C# business-rule oracle

## What is actually verified

The original C# repository `waleed875/memo_project` is read-only and pinned
at `dd8eb2ba57864eac2371a3addd0b786ccefa2367`. CI checks out only
the original `AuditMemoSystem.Core` source, compiles it, then runs a small
oracle runner that **calls the real C# implementations** (not a Java
reimplementation). A shared 50-case file is executed by both that runner and
the existing Spring Boot domain policies.

Compared values: number normalization; positive numeric parsing with Arabic
and Persian digits; DateOnly five-day delay classifications and boundary
dates; active/required indicator status classification; role/status edit
restrictions; submission and draft save preflight; file extension/signature
validation.

The result is a strict case-ID matched comparison, with each differing
original-vs-Spring outcome emitted on failure. Running the Java test outside
the dedicated workflow validates fixture integrity and Java behavior only;
setting `MEMO_CSHARP_ORACLE` to an actual oracle output file enables the
strict parity comparison. Dedicated CI always sets this variable and fails
if the reference output is missing.

## Explicit limitations

This is **core pure business-rule parity**, not a full end-to-end equivalent
of C# SaveNoteAsync and not authorization, database history, audit,
transaction, source, attachment storage, or response/API parity.
The C# production path supplies the *effective active* definitions and the
reference runner mirrors that selection. Java intentionally additionally
rejects duplicate field IDs and unsafe file names; these security-policy
differences are outside the shared common-case matrix and must be reviewed
separately.

C# future-date validation uses an operational clock; shared cases use
historic fixed dates and a far-future negative case, avoiding current-day
flakiness. More production cases and snapshots are required before launch.

No merge to main, no public note-write HTTP endpoint, no production
deployment, and no modifications to the original .NET repository.
