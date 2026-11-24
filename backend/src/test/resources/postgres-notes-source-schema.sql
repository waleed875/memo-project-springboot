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
-- DB-side constraints are deliberately tested rather than trusting Java.
create or replace function core.validate_source_exit_reference_test()
returns trigger language plpgsql as $$
declare source_company uuid;
declare exit_company uuid;
declare type_code text;
declare source_kind text;
begin
    select n.company_id, s.source_type into source_company, source_kind
    from core.note_sources s join core.notes n on n.id = s.note_id
    where s.id = new.source_id and not n.is_deleted;
    select n.company_id, nt.code into exit_company, type_code
    from core.notes n join core.note_types nt on nt.id = n.note_type_id
    where n.id = new.exit_note_id and not n.is_deleted;
    if source_kind is distinct from 'EXIT'
       or source_company is distinct from exit_company
       or type_code is distinct from 'EXIT' then
        raise exception using errcode='23514',
            message='invalid cross-company or cross-type exit source reference';
    end if;
    return new;
end; $$;
create trigger trg_validate_source_exit_reference_test
before insert or update on core.note_source_exit_refs
for each row execute function core.validate_source_exit_reference_test();
