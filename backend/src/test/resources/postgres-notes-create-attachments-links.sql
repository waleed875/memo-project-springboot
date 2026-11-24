-- TEST ONLY. A narrow addition matching migrations 013 and 068.
-- Do NOT apply these snippets to a real migrated database.
alter table core.note_links
    add column created_by uuid references auth.users(id);
alter table core.note_links
    add constraint ck_note_links_no_self_test
    check (source_note_id <> related_note_id);
create unique index ux_note_links_pair_test
    on core.note_links(least(source_note_id, related_note_id),
                       greatest(source_note_id, related_note_id));
create table core.note_attachments (
    id uuid primary key default gen_random_uuid(),
    note_id uuid not null references core.notes(id) on delete cascade,
    file_name text not null,
    content_type varchar(120) not null,
    file_data bytea not null,
    file_size bigint not null default 0,
    created_at timestamptz not null default clock_timestamp(),
    created_by uuid references auth.users(id)
);
create index idx_note_attachments_note_id_test
    on core.note_attachments(note_id);
