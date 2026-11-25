-- TEST ONLY. A narrow dashboard aggregate extension, not a production migration.
create table core.duplicate_incidents (
    id uuid primary key default gen_random_uuid(),
    classification varchar(50) not null,
    created_note_id uuid references core.notes(id),
    matched_note_id uuid references core.notes(id)
);
