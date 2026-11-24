-- Test-only subset of migration 063, replacing simplified test source table.
-- Not suitable for production or a replacement for migrations 001-070.
drop table core.note_sources;
create table core.note_sources (
    id uuid primary key default gen_random_uuid(),
    note_id uuid not null references core.notes(id) on delete cascade,
    source_type varchar(20) not null,
    other_description text,
    created_by uuid references auth.users(id),
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    constraint ux_note_sources_note unique (note_id),
    constraint ck_note_sources_type check (
        source_type in ('EXIT','INVOICE','LOOT','OTHER')),
    constraint ck_note_sources_other_description check (
        source_type <> 'OTHER' or nullif(btrim(other_description),'') is not null)
);
create table core.note_source_exit_refs (
    id uuid primary key default gen_random_uuid(),
    source_id uuid not null references core.note_sources(id) on delete cascade,
    exit_note_id uuid not null references core.notes(id) on delete restrict,
    reference_order integer not null default 1,
    created_at timestamp not null default now(),
    unique (source_id, exit_note_id),
    check (reference_order > 0)
);
create table core.note_source_invoices (
    id uuid primary key default gen_random_uuid(),
    source_id uuid not null references core.note_sources(id) on delete cascade,
    invoice_number varchar(120) not null,
    invoice_date date,
    destination text,
    attachment_file_name varchar(255),
    attachment_content_type varchar(120),
    attachment_data bytea,
    created_at timestamp not null default now(),
    check(nullif(btrim(invoice_number),'') is not null)
);
create table core.note_source_loot (
    source_id uuid primary key references core.note_sources(id) on delete cascade,
    description text not null,
    created_at timestamp not null default now(),
    check(nullif(btrim(description),'') is not null)
);
-- Trigger logic is applied separately with a custom SQL script separator.
