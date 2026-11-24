-- TEST ONLY: selected columns and idempotency table from legacy migrations 057/062.
-- Never apply to a production database; this is not migrations 001-070.
create table core.note_create_operations (
    operation_id uuid primary key,
    note_id uuid not null,
    requested_by uuid not null references auth.users(id),
    fingerprint varchar(64) not null,
    created_at timestamp not null default now(),
    constraint ux_note_create_operations_note_id unique(note_id),
    constraint fk_note_create_operations_note
        foreign key(note_id) references core.notes(id)
        deferrable initially deferred,
    constraint ck_note_create_operations_fingerprint
        check(fingerprint ~ '^[0-9a-f]{64}$')
);
