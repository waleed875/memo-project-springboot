-- Isolated TEST FIXTURE, derived from the read-only legacy migration files.
-- core.companies: 002_create_tables.sql, 008_simplify_companies.sql
-- core.factories: 016_add_factories.sql, 017_link_factories_to_companies.sql
-- core.production_lines: 024_add_production_lines.sql
-- This is NOT the full schema and must NEVER be applied to production.
create schema if not exists core;

create table core.companies (
    id uuid primary key default gen_random_uuid(),
    name varchar(255) not null,
    is_active boolean not null default true,
    is_deleted boolean not null default false,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

create table core.factories (
    id uuid primary key default gen_random_uuid(),
    name varchar(200) not null,
    is_active boolean not null default true,
    is_deleted boolean not null default false,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    company_id uuid references core.companies(id)
);

create unique index ux_factories_company_name_active
    on core.factories(company_id, lower(name)) where is_deleted = false;

create table core.production_lines (
    id uuid primary key default gen_random_uuid(),
    factory_id uuid not null references core.factories(id),
    name varchar(200) not null,
    is_active boolean not null default true,
    is_deleted boolean not null default false,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);
create unique index ux_production_lines_factory_name_active
    on core.production_lines(factory_id, lower(name)) where is_deleted = false;


-- Additional only-for-TEST tables derived from legacy 002 and system.app_settings.
-- No full migration, no production DDL.
create schema if not exists auth;
create schema if not exists audit;
create schema if not exists system;
create table auth.roles (
    id uuid primary key,
    code varchar(50) unique not null,
    name_ar varchar(100) not null,
    is_active boolean not null default true
);
create table auth.users (
    id uuid primary key,
    username varchar(100) unique not null,
    password_hash text not null,
    full_name varchar(255) not null,
    role_id uuid not null references auth.roles(id),
    is_active boolean not null default true,
    is_deleted boolean not null default false
);
create table system.app_settings (
    key varchar(100) primary key,
    value text,
    updated_at timestamp not null default now()
);
create table audit.logs (
    id bigserial primary key,
    user_id uuid references auth.users(id),
    action_type varchar(100) not null,
    schema_name varchar(100),
    table_name varchar(100),
    record_id uuid,
    description text,
    old_value jsonb,
    new_value jsonb,
    created_at timestamp not null default now()
);
-- Only columns required for the relationship checks, not the full core.notes schema.
create table core.notes (
    id uuid primary key,
    company_id uuid not null references core.companies(id),
    factory_id uuid references core.factories(id),
    production_line_id uuid references core.production_lines(id),
    is_deleted boolean not null default false
);

-- Note-definition read/impact fixture from the original 002_create_tables.sql.
create table core.note_types (
    id uuid primary key,
    code varchar(50) unique not null,
    name_ar varchar(100) not null,
    description text,
    is_active boolean not null default true,
    is_deleted boolean not null default false
);
create table core.note_fields (
    id uuid primary key,
    code varchar(80) unique not null,
    name_ar varchar(150) not null,
    description text,
    default_sort_order int not null default 0,
    is_active boolean not null default true,
    is_deleted boolean not null default false
);
create table core.note_type_fields (
    id uuid primary key,
    note_type_id uuid not null references core.note_types(id),
    field_id uuid not null references core.note_fields(id),
    sort_order int not null,
    is_required boolean not null default true,
    is_active boolean not null default true,
    is_deleted boolean not null default false,
    unique(note_type_id, field_id)
);
alter table core.notes add column note_type_id uuid references core.note_types(id);
create table core.note_field_values (
    id uuid primary key,
    note_id uuid not null references core.notes(id) on delete cascade,
    field_id uuid not null references core.note_fields(id),
    field_value smallint not null,
    unique(note_id, field_id)
);
