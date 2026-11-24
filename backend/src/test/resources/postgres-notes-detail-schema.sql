-- TEST ONLY: schema additions for the note-detail projection.
-- Reference: C# database migrations 002, 009, 010, 012, 013, 023, 024,
-- 063, 069, 070. Never apply this partial fixture to production.
alter table core.notes
    add column note_number varchar(100) not null default 'TEST',
    add column writing_date date,
    add column arrival_at date not null default current_date,
    add column image_path text,
    add column file_path text,
    add column attachment_file_name text,
    add column attachment_content_type varchar(120),
    add column attachment_data bytea,
    add column notes text,
    add column status varchar(50) not null default 'DRAFT',
    add column created_by uuid references auth.users(id),
    add column updated_by uuid references auth.users(id),
    add column approved_by uuid references auth.users(id),
    add column approved_at timestamp,
    add column reviewer_id uuid references auth.users(id),
    add column review_started_at timestamp,
    add column return_reason text,
    add column duplicate_reason text,
    add column is_loot boolean not null default false,
    add column is_invoice boolean not null default false,
    add column created_at timestamp not null default now(),
    add column updated_at timestamp not null default now();

alter table core.note_field_values add column inactive_reason text;
alter table auth.users add column updated_at timestamp not null default now();

create table core.note_review_history (
    id uuid primary key default gen_random_uuid(),
    note_id uuid not null references core.notes(id),
    action varchar(80) not null,
    reason text,
    user_id uuid references auth.users(id),
    created_at timestamp not null default now()
);

create table core.note_sources (
    note_id uuid not null references core.notes(id),
    source_type varchar(50) not null,
    primary key(note_id,source_type)
);

create table core.note_links (
    source_note_id uuid not null references core.notes(id),
    related_note_id uuid not null references core.notes(id),
    primary key(source_note_id,related_note_id)
);
create view core.v_note_links_compat as
select source_note_id,related_note_id from core.note_links;
